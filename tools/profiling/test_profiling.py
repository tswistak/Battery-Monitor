#!/usr/bin/env python3
# Copyright (c) 2026 Tomasz Świstak
#
# This program is free software: you can redistribute it and/or modify
# it under the terms of the GNU General Public License as published by
# the Free Software Foundation, either version 3 of the License, or
# (at your option) any later version.
#
# This program is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
# GNU General Public License for more details.
#
# You should have received a copy of the GNU General Public License
# along with this program. If not, see <https://www.gnu.org/licenses/>.

"""Host-only checks: no device access, downloads, or APK changes."""

import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
from types import SimpleNamespace

import analyze
import capture
import run as benchmark_run
import summarize


def proc_stat_fixture(pid, user, system, start, comm="worker (old) thread"):
    return f"{pid} ({comm}) S 1 2 3 4 5 6 7 8 9 10 {user} {system} 20 21 22 23 24 25 {start} 30 31"


def cpu_snapshot_fixture(entries, clock="100", read_brackets=None):
    lines = [f"clockTicksPerSecond:{clock}", "snapshotUptime:100.00 200.00"]
    for process in capture.PROCESSES:
        lines.append(f"process:{process}")
        for pid, raw in entries.get(process, []):
            lines.append(f"pid:{pid}")
            if read_brackets:
                lines.append(f"statReadStartUptime:{read_brackets[0]}")
            lines.append(f"stat:{raw}")
            if read_brackets:
                lines.append(f"statReadEndUptime:{read_brackets[1]}")
    return "\n".join(lines) + "\n"


class ProfilingTest(unittest.TestCase):
    def test_proc_cpu_normalizes_each_pid_only_with_valid_observation_brackets(self):
        with tempfile.TemporaryDirectory() as directory:
            before, after = Path(directory) / "before.txt", Path(directory) / "after.txt"
            first = {capture.PACKAGE: [(100, proc_stat_fixture(100, 10, 3, 5000))]}
            last = {capture.PACKAGE: [(100, proc_stat_fixture(100, 30, 8, 5000))]}
            before.write_text(cpu_snapshot_fixture(first, read_brackets=("100.00 200.00", "100.02 200.00")))
            after.write_text(cpu_snapshot_fixture(last, read_brackets=("102.00 200.00", "102.02 200.00")))
            metric = analyze.proc_cpu_summary(before, after)["processes"][capture.PACKAGE]
            self.assertEqual(metric["cpu_ms"], 250)
            self.assertEqual(metric["observation_interval"]["lower_ms"], 1960)
            self.assertEqual(metric["observation_interval"]["upper_ms"], 2040)
            self.assertEqual(metric["observation_interval"]["midpoint_estimate_ms"], 2000)
            self.assertEqual(metric["one_core_percent"]["midpoint_estimate"], 12.5)
            self.assertAlmostEqual(metric["one_core_percent"]["lower_interval_estimate"], 25000 / 2040)
            for brackets in (None, ("invalid", "102.02 200.00"), ("102.03 200.00", "102.02 200.00"),
                             ("100.00 200.00", "100.01 200.00")):
                with self.subTest(brackets=brackets):
                    after.write_text(cpu_snapshot_fixture(last, read_brackets=brackets))
                    metric = analyze.proc_cpu_summary(before, after)["processes"][capture.PACKAGE]
                    self.assertEqual(metric["cpu_ms"], 250)
                    self.assertEqual(metric["observation_interval"]["status"], "unavailable")
                    self.assertEqual(metric["one_core_percent"]["status"], "unavailable")
            after.write_text(cpu_snapshot_fixture(last, read_brackets=("102.00 200.00", "102.02 200.00")))
            before.write_text(cpu_snapshot_fixture({}))
            cold = analyze.proc_cpu_summary(before, after, confirmed_cold=True)["processes"][capture.PACKAGE]
            self.assertEqual(cold["cpu_ms"], 380)
            self.assertEqual(cold["one_core_percent"]["status"], "unavailable")

    def test_gfxinfo_action_boundaries_do_not_clip_long_or_exceptional_rows(self):
        raw = ("Total frames rendered: 20\nJanky frames: 20 (100.00%)\n---PROFILEDATA---\n"
               "Flags,IntendedVsync,FrameCompleted,\n0,100000000,900000000,\n"
               "0,1100000000,1900000000,\n0,2100000000,3100000000,\n"
               "0,1500000000,6500000000,\n0,999500000,1000500000,\n"
               "0,1000500000,1001500000,\n0,2000500000,2001500000,\n"
               "1,1200000000,1300000000,\n0,1400000000,9223372036854775807,\n---PROFILEDATA---\n")
        metadata = {"actionStartUptimeMillis": 1000, "actionEndUptimeMillis": 2000,
                    "gfxinfoWindow": {"clock": "SystemClock.uptimeMillis", "resetBeforeUptimeMillis": 900,
                    "resetAfterUptimeMillis": 901, "dumpBeforeUptimeMillis": 2001, "dumpAfterUptimeMillis": 2002}}
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "frames.txt"
            path.write_text(raw)
            result = analyze.gfxinfo_summary(path, metadata)
            old = analyze.gfxinfo_summary(path)
            invalid = analyze.gfxinfo_summary(path, metadata | {"actionEndUptimeMillis": 3000})
            self.assertEqual(path.read_text(), raw)
            path.write_text(raw.replace("0,1100000000,1900000000,\n", ""))
            no_contained = analyze.gfxinfo_summary(path, metadata)
            path.write_text(raw.replace("0,1100000000,1900000000,\n", "").replace("0,1500000000,6500000000,\n", ""))
            no_started = analyze.gfxinfo_summary(path, metadata)
        self.assertEqual([row["action_window"].get("classification") for row in result["retained_frames"]],
                         ["before", "inside", "after", "straddles", "straddles", "straddles", "straddles", "inside", None])
        self.assertEqual([row["action_start_window"].get("classification") for row in result["retained_frames"]],
                         ["before", "inside", "after", "inside", "before", "boundary", "boundary", "inside", None])
        self.assertEqual(result["excluded_frame_count"], 2)
        self.assertEqual(result["retained_frame_completed_minus_intended_vsync_ms"]["count"], 7)
        self.assertEqual(result["retained_frame_completed_minus_intended_vsync_ms"]["max"], 5000)
        self.assertEqual(result["retained_frame_completed_minus_intended_vsync_ms"],
                         old["retained_frame_completed_minus_intended_vsync_ms"])
        self.assertEqual(old["action_window"]["status"], "unavailable")
        self.assertEqual(invalid["action_window"]["status"], "unavailable")
        contained = result["action_contained_frame_completed_minus_intended_vsync_ms"]
        self.assertEqual(contained["count"], 1)
        self.assertEqual(contained["p50"], 800)
        self.assertEqual(contained["eligible_cohort_counts"],
                         {"before": 1, "inside": 1, "after": 1, "straddles": 4, "unavailable": 0})
        for value in (old, invalid, no_contained):
            self.assertEqual(value["action_contained_frame_completed_minus_intended_vsync_ms"]["status"], "unavailable")
            self.assertNotIn("p50", value["action_contained_frame_completed_minus_intended_vsync_ms"])
        self.assertEqual(old["action_contained_frame_completed_minus_intended_vsync_ms"]["eligible_cohort_counts"]["unavailable"], 7)
        self.assertEqual(no_contained["action_contained_frame_completed_minus_intended_vsync_ms"]["eligible_cohort_counts"]["straddles"], 4)
        self.assertEqual(no_contained["retained_frame_completed_minus_intended_vsync_ms"]["max"], 5000)
        started = result["action_started_frame_completed_minus_intended_vsync_ms"]
        self.assertEqual(started["count"], 2)
        self.assertEqual(started["max"], 5000)
        self.assertEqual(started["eligible_start_cohort_counts"],
                         {"before": 2, "inside": 2, "after": 1, "boundary": 2, "unavailable": 0})
        self.assertEqual(no_contained["action_started_frame_completed_minus_intended_vsync_ms"]["max"], 5000)
        for value in (old, invalid, no_started):
            self.assertEqual(value["action_started_frame_completed_minus_intended_vsync_ms"]["status"], "unavailable")
            self.assertNotIn("p50", value["action_started_frame_completed_minus_intended_vsync_ms"])

    def test_proc_memory_preserves_bytes_and_raw_pages_with_identity_and_missing_field_guards(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            before, after = root / "before.txt", root / "after.txt"
            first = proc_stat_fixture(100, 10, 3, 5000).removesuffix("30 31") + "4096 2"
            last = proc_stat_fixture(100, 30, 8, 5000).removesuffix("30 31") + "8192 7"
            before.write_text(cpu_snapshot_fixture({capture.PACKAGE: [(100, first)]}))
            after.write_text(cpu_snapshot_fixture({capture.PACKAGE: [(100, last)]}))
            proc = analyze.proc_cpu_summary(before, after)
            metric = proc["processes"][capture.PACKAGE]
            self.assertEqual(metric["memory_before"], {"vsize_bytes": 4096, "rss_pages": 2})
            self.assertEqual(metric["memory_after"], {"vsize_bytes": 8192, "rss_pages": 7})
            item = {"status": "available", "gfxinfo": {}, "atrace": {}, "proc_cpu": proc}
            path = root / "analysis.json"
            path.write_text(json.dumps({"legacy_results": [{"path": str(root / "legacy.json"), "sha256": "a" * 64,
                             "scenario": "memory", "iterations": [item]}]}))
            summary = summarize.aggregate([path])["memory"]
            self.assertEqual(summary["memory"]["status"], "available")
            self.assertEqual(summary["metrics"][f"proc_rss_pages_after/{capture.PACKAGE}"]["p50"], 7)
            self.assertEqual(summary["metrics"][f"proc_vsize_bytes_before/{capture.PACKAGE}"]["p50"], 4096)
            after.write_text(cpu_snapshot_fixture({capture.PACKAGE: [(100, last.removesuffix("7") + "unavailable")]}))
            missing = analyze.proc_cpu_summary(before, after)["processes"][capture.PACKAGE]
            self.assertEqual(missing["memory_after"]["rss_pages"]["status"], "unavailable")
            self.assertEqual(missing["cpu_ms"], 250)
            after.write_text(cpu_snapshot_fixture({capture.PACKAGE: [(100, last.rsplit(" ", 2)[0])]}))
            absent = analyze.proc_cpu_summary(before, after)["processes"][capture.PACKAGE]
            self.assertEqual(absent["memory_after"]["vsize_bytes"]["status"], "unavailable")
            self.assertEqual(absent["memory_after"]["rss_pages"]["status"], "unavailable")
            self.assertEqual(absent["cpu_ms"], 250)
            after.write_text(cpu_snapshot_fixture({capture.PACKAGE: [(100, last.replace("5000", "5001"))]}))
            reused = analyze.proc_cpu_summary(before, after)["processes"][capture.PACKAGE]
            self.assertEqual(reused["status"], "unavailable")
            self.assertNotIn("memory_after", reused)

    def test_summary_excludes_failed_rows_and_preserves_missing_metrics_and_real_zero(self):
        frame = {"exclusions": [], "frame_completed_minus_intended_vsync_ms": 10}
        valid = {"status": "available", "am_start": {"status": "available", "timings": {"am_start_TotalTime_ms": 0}},
                 "gfxinfo": {"retained_frames": [frame], "excluded_frame_count": 0,
                             "android_reported_total_frames": 0, "android_reported_jank": {"percent": 0}},
                 "atrace": {"trace_health": [], "main_thread_cpu": analyze.unavailable("Integrity error")},
                 "proc_cpu": {"processes": {capture.PACKAGE: {"status": "available", "cpu_ms": 0,
                      "user_cpu_ms": 0, "system_cpu_ms": 0, "clock_ticks_per_second": 100, "basis": "same_process_delta"}}}}
        failed = {**valid, "status": "unavailable", "gfxinfo": {"retained_frames": [{**frame,
                  "frame_completed_minus_intended_vsync_ms": 10000}], "excluded_frame_count": 0}}
        result = {"path": "/absent/benchmark/legacy-case.json", "sha256": "a" * 64,
                  "scenario": "case", "iterations": [valid, failed]}
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "analysis.json"
            path.write_text(json.dumps({"legacy_results": [result]}))
            summary = summarize.aggregate([path])["case"]
            with self.assertRaisesRegex(ValueError, "double-count"):
                summarize.aggregate([path, path])
        self.assertEqual(summary["status"], "partial")
        self.assertEqual(summary["valid_iterations"], 1)
        self.assertEqual(summary["metrics"]["am_start_TotalTime_ms"]["p50"], 0)
        self.assertEqual(summary["metrics"]["gfxinfo_retained_FrameCompleted_minus_IntendedVsync_ms"]["p50"], 10)
        self.assertEqual(summary["metrics"]["gfxinfo_android_reported_janky_frame_percent_per_iteration"]["status"], "unavailable")
        self.assertEqual(summary["metrics"][f"scheduler_main_thread_cpu_ms/{capture.PACKAGE}"]["status"], "unavailable")
        self.assertEqual(summary["metrics"][f"proc_cpu_ms/{capture.PACKAGE}"]["p50"], 0)
        self.assertEqual(summary["memory"]["status"], "unavailable")

    def test_summary_warns_on_different_harness_and_undefined_zero_baseline_percentage(self):
        def side(harness, value):
            return {"valid_iterations": 5, "metrics": {"metric_ms": analyze.distribution([value])},
                    "sources": [{"provenance": {"serial": "oneplus", "config": {"anchorMillis": 1},
                    "measurementBackend": "legacyAtrace", "apk_sha256": {benchmark_run.BENCHMARK_PACKAGE: harness}}}]}
        before, after = side("first", 0), side("second", 10)
        compared = summarize.compare({"case": before}, {"case": after})["case"]
        self.assertTrue(any("harness APK hashes differ" in value for value in compared["comparison_warnings"]))
        self.assertEqual(compared["changes"]["metric_ms"]["p50_change"], 10)
        self.assertEqual(compared["changes"]["metric_ms"]["p50_percent_change"]["status"], "unavailable")

    def test_proc_cpu_uses_old_kernel_stat_fields_with_spaces_and_parentheses_in_comm(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            before, after = root / "before.txt", root / "after.txt"
            before.write_text(cpu_snapshot_fixture({capture.PACKAGE: [(100, proc_stat_fixture(100, 10, 3, 5000))]}))
            after.write_text(cpu_snapshot_fixture({capture.PACKAGE: [(100, proc_stat_fixture(100, 30, 8, 5000))]}))
            result = analyze.proc_cpu_summary(before, after)
            after.write_bytes(before.read_bytes())
            self.assertEqual(analyze.proc_cpu_summary(before, after)["processes"][capture.PACKAGE]["cpu_ms"], 0)
        self.assertEqual(result["status"], "available")
        metric = result["processes"][capture.PACKAGE]
        self.assertEqual(metric["cpu_ms"], 250)
        self.assertEqual(metric["user_cpu_ms"], 200)
        self.assertEqual(metric["system_cpu_ms"], 50)
        self.assertEqual(metric["basis"], "same_process_delta")
        self.assertEqual(result["before"]["processes"][capture.PACKAGE][0]["comm"], "worker (old) thread")
        self.assertEqual(result["processes"][capture.PROCESSES[1]]["status"], "unavailable")

    def test_proc_cpu_rejects_pid_reuse_invalid_clock_and_missing_counters(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            before, after = root / "before.txt", root / "after.txt"
            baseline = cpu_snapshot_fixture({capture.PACKAGE: [(100, proc_stat_fixture(100, 10, 3, 5000))]})
            before.write_text(baseline)
            for pid, raw in ((100, proc_stat_fixture(100, 30, 8, 5001)),
                             (101, proc_stat_fixture(101, 30, 8, 5000)),
                             (100, proc_stat_fixture(100, 9, 8, 5000)),
                             (100, "unavailable"), (100, "100 (old worker) S 1 2"),
                             (100, proc_stat_fixture(999, 30, 8, 5000))):
                with self.subTest(pid=pid, raw=raw):
                    after.write_text(cpu_snapshot_fixture({capture.PACKAGE: [(pid, raw)]}))
                    self.assertEqual(analyze.proc_cpu_summary(before, after)["status"], "unavailable")
            for clock in ("0", "-1", "unavailable", "true", "", "250"):
                with self.subTest(clock=clock):
                    after.write_text(cpu_snapshot_fixture({capture.PACKAGE: [(100, proc_stat_fixture(100, 30, 8, 5000))]}, clock))
                    self.assertEqual(analyze.proc_cpu_summary(before, after)["status"], "unavailable")

    def test_proc_cpu_since_birth_requires_confirmed_cold_and_explicit_empty_before_process(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            before, after = root / "before.txt", root / "after.txt"
            before.write_text(cpu_snapshot_fixture({}))
            after.write_text(cpu_snapshot_fixture({capture.PACKAGE: [(100, proc_stat_fixture(100, 10, 2, 5000))]}))
            self.assertEqual(analyze.proc_cpu_summary(before, after)["status"], "unavailable")
            cold = analyze.proc_cpu_summary(before, after, confirmed_cold=True)
            self.assertEqual(cold["processes"][capture.PACKAGE]["cpu_ms"], 120)
            self.assertEqual(cold["processes"][capture.PACKAGE]["basis"], "since_process_birth_during_confirmed_cold_start")
            before.write_text(cpu_snapshot_fixture({}).replace(f"process:{capture.PACKAGE}\n", ""))
            self.assertEqual(analyze.proc_cpu_summary(before, after, confirmed_cold=True)["status"], "unavailable")

    def test_gfxinfo_retains_rows_exclusions_and_real_long_frames(self):
        raw = ("Total frames rendered: 1000\nJanky frames: 100 (10.00%)\nWindow: profile/main\n"
               "---PROFILEDATA---\nFlags,IntendedVsync,FrameCompleted,\n"
               "0,1000000,2000000,\n0,3000000,5003000000,\n"
               "0,4000000,9223372036854775807,\n1,5000000,6000000,\n"
               "0,not-an-integer,8000000,\n0,9000000,0,\n---PROFILEDATA---\n")
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "gfxinfo.txt"
            path.write_text(raw)
            result = analyze.gfxinfo_summary(path)
            self.assertEqual(path.read_text(), raw)
        self.assertEqual(result["retained_frame_count"], 6)
        self.assertEqual(result["excluded_frame_count"], 4)
        self.assertEqual(result["retained_frame_completed_minus_intended_vsync_ms"]["max"], 5000)
        self.assertEqual(result["android_reported_total_frames"], 1000)
        self.assertEqual(result["android_reported_jank"], {"frames": 100, "percent": 10})
        self.assertIn("sentinel", result["retained_frames"][2]["exclusions"][0])
        self.assertEqual(result["retained_frames"][2]["fields"]["FrameCompleted"], 2**63 - 1)

    def test_missing_gfxinfo_is_unavailable_instead_of_zero_jank(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "gfxinfo.txt"
            path.write_text("No process found\n")
            result = analyze.gfxinfo_summary(path)
        self.assertEqual(result["status"], "unavailable")
        self.assertEqual(result["android_reported_jank"]["status"], "unavailable")
        self.assertEqual(result["retained_frame_completed_minus_intended_vsync_ms"]["status"], "unavailable")

    def test_am_start_times_keep_source_names_zero_and_actual_launch_state(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "am-start.txt"
            path.write_text("Status: ok\nLaunchState: HOT\nThisTime: 0\nTotalTime: 0\nWaitTime: 12\nComplete\n")
            hot = analyze.am_start_summary(path, "HOT")
            warm = analyze.am_start_summary(path, "WARM")
            path.write_text("Status: ok\nLaunchState: COLD\n")
            missing = analyze.am_start_summary(path, "COLD")
        self.assertEqual(hot["status"], "available")
        self.assertEqual(hot["timings"]["am_start_ThisTime_ms"], 0)
        self.assertEqual(hot["timings"]["am_start_WaitTime_ms"], 12)
        self.assertEqual(warm["status"], "unavailable")
        self.assertEqual(warm["reported_launch_state"], "HOT")
        self.assertEqual(missing["status"], "unavailable")
        self.assertNotIn("timeToInitialDisplayMs", hot["timings"])

    def test_legacy_artifacts_and_pid_maps_reject_ambiguous_or_escaping_values(self):
        with tempfile.TemporaryDirectory() as directory:
            for name in ("../history.db", "/outside.trace", "nested/trace", "nested\\trace"):
                with self.assertRaises(ValueError):
                    analyze.legacy_artifact(Path(directory), name)
        base = {"traceMarkerPid": 50, "traceSection": "battery-profile/test/1",
                "processPids": {capture.PACKAGE: [100]}, "processTids": {"100": [100, 101]}}
        sql = analyze.legacy_trace_sql(base)
        self.assertIn("p.pid=50", sql)
        self.assertIn("s.name='battery-profile/test/1'", sql)
        self.assertIn("MIN(s.ts+s.dur,w.ts+w.dur)-MAX(s.ts,w.ts)", sql)
        for invalid in ({**base, "traceMarkerPid": True},
                        {**base, "markerType": "unknown"},
                        {**base, "markerType": "nativeAsync", "markerCookie": True},
                        {**base, "markerType": "nativeAsync", "markerCookie": 2**31},
                        {**base, "processPids": {capture.PACKAGE: [True]}},
                        {**base, "processPids": {"production": [100]}},
                        {**base, "processPids": {capture.PACKAGE: [100], capture.PROCESSES[1]: [200]},
                         "processTids": {"100": [101], "200": [101]}}):
            with self.assertRaises(ValueError):
                analyze.legacy_trace_sql(invalid)

    def test_native_async_markers_from_different_writer_tids_with_real_trace_processor(self):
        binary = os.environ.get("PROFILING_TRACE_PROCESSOR")
        if not binary:
            self.skipTest("Set PROFILING_TRACE_PROCESSOR to an existing native binary for the parser integration check")
        iteration = {"traceMarkerPid": 50, "traceSection": "battery-profile/test/1", "markerType": "nativeAsync",
                     "markerCookie": 123, "processPids": {capture.PACKAGE: [100]}, "processTids": {"100": [100, 101]}}
        fixture = ("# tracer: nop\n# entries-in-buffer/entries-written: 5/5   #P:2\n"
                   "# TASK-PID CPU# TIMESTAMP FUNCTION\n"
                   "<idle>-0 [000] .... 0.990000: sched_switch: prev_comm=idle prev_pid=0 prev_prio=120 prev_state=R ==> next_comm=app next_pid=100 next_prio=120\n"
                   "rootsh-300 [001] .... 1.000000: tracing_mark_write: S|50|battery-profile/test/1|123\n"
                   "app-100 [000] .... 1.002000: sched_switch: prev_comm=app prev_pid=100 prev_prio=120 prev_state=R ==> next_comm=worker next_pid=101 next_prio=120\n"
                   "rootsh-301 [001] .... 1.004000: tracing_mark_write: F|50|battery-profile/test/1|123\n"
                   "worker-101 [000] .... 1.006000: sched_switch: prev_comm=worker prev_pid=101 prev_prio=120 prev_state=R ==> next_comm=idle next_pid=0 next_prio=120\n")
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "async.atrace"
            path.write_text(fixture)
            result = analyze.legacy_trace_summary(path, Path(binary), iteration)
            self.assertEqual(result["status"], "available")
            self.assertEqual(result["window"], [{"ts_ns": 1000000000, "duration_ns": 4000000}])
            self.assertEqual(result["native_async_raw_markers"], {"S": 1, "F": 1})
            self.assertEqual(result["marker_identity"], {"pid": 50, "section": "battery-profile/test/1", "cookie": 123})
            self.assertEqual(result["main_thread_cpu"][0]["cpu_ms"], 2)
            self.assertEqual([row["cpu_ms"] for row in result["observed_task_cpu"]], [2, 2])
            for changed in ({"markerCookie": 124}, {"traceMarkerPid": 51}):
                self.assertEqual(analyze.legacy_trace_summary(path, Path(binary), iteration | changed)["status"], "unavailable")
            path.write_text(fixture.replace("prev_comm=worker prev_pid=101", "prev_comm=worker prev_pid=999"))
            unhealthy = analyze.legacy_trace_summary(path, Path(binary), iteration)
            self.assertEqual(unhealthy["status"], "available")
            self.assertEqual(unhealthy["scheduler_cpu_warning"]["errors"][0]["name"], "mismatched_sched_switch_tids")
            self.assertEqual(unhealthy["main_thread_cpu"]["status"], "unavailable")
            self.assertEqual(unhealthy["main_thread_cpu"]["untrusted_estimates"][0]["cpu_ms"], 2)
            self.assertEqual(unhealthy["observed_task_cpu"]["status"], "unavailable")
            path.write_text(fixture.replace("rootsh-301", "rootsh-302 [001] .... 1.003500: tracing_mark_write: S|50|battery-profile/test/1|123\nrootsh-301"))
            self.assertEqual(analyze.legacy_trace_summary(path, Path(binary), iteration)["status"], "unavailable")

    def test_legacy_trace_requires_one_marker_and_keeps_missing_tgid_coverage_partial(self):
        iteration = {"traceMarkerPid": 50, "traceSection": "battery-profile/test/1",
                     "processPids": {capture.PACKAGE: [100]}, "processTids": {"100": [100, 101]}}
        rows = {"window": [{"ts_ns": 1000, "duration_ns": 4000}],
                "main_thread_cpu": [{"process": capture.PACKAGE, "pid": 100, "cpu_ms": 0.002}],
                "observed_task_cpu": [{"process": capture.PACKAGE, "pid": 100, "tid": 101, "cpu_ms": 0.001}],
                "mapped_process_cpu": [{"process": capture.PACKAGE, "pid": 100, "cpu_ms": 0.002}],
                "gc_database_named_slices": [], "shell_process_candidates": [], "trace_health": []}
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "trace.atrace"
            path.write_text("# entries-in-buffer/entries-written: 1/1   #P:1\n"
                            "worker-101 [000] ...1 1.004: sched_switch: prev_pid=101 next_pid=0\n")
            for marker_count in (1, 0, 2):
                with self.subTest(marker_count=marker_count):
                    metrics = rows | {"window": rows["window"] * marker_count}
                    csv = '"metric","data"\n' + "".join(
                        f'"{name}","{json.dumps(value).encode().hex()}"\n' for name, value in metrics.items())
                    with patch("analyze.subprocess.run", return_value=SimpleNamespace(returncode=0, stdout=csv, stderr="")):
                        result = analyze.legacy_trace_summary(path, Path("processor"), iteration)
                    self.assertEqual(result["status"], "available" if marker_count == 1 else "unavailable")
                    if marker_count == 1:
                        self.assertFalse(result["tgid_mapping"]["complete"])
                        self.assertEqual(result["mapped_process_cpu"]["status"], "unavailable")
                        self.assertEqual(result["observed_task_cpu"][0]["tid"], 101)
                        self.assertEqual(result["gc_database_named_slices"]["status"], "unavailable")

    def test_legacy_results_keep_valid_am_and_cpu_when_frame_samples_are_unavailable(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "frames.txt").write_text("Total frames rendered: 1\n---PROFILEDATA---\n"
                                             "Flags,IntendedVsync,FrameCompleted,\n1,1000,2000,\n---PROFILEDATA---\n")
            (root / "trace.atrace").write_text("fixture")
            (root / "am.txt").write_text("Status: ok\nLaunchState: COLD\nTotalTime: 20\nWaitTime: 22\n")
            (root / "cpu-before.txt").write_text(cpu_snapshot_fixture({}))
            (root / "cpu-after.txt").write_text(cpu_snapshot_fixture({capture.PACKAGE: [(100, proc_stat_fixture(100, 10, 2, 5000))]}))
            data = {"schemaVersion": 1, "measurementBackend": "legacyAtrace", "packageName": capture.PACKAGE,
                    "scenario": "startupCold", "iterations": [{"iteration": 1, "status": "passed",
                    "traceCaptureStatus": "captured", "frameMetricsStatus": "unavailable",
                    "frameStatsFile": "frames.txt", "traceFile": "trace.atrace",
                    "cpuCountersBeforeFile": "cpu-before.txt", "cpuCountersAfterFile": "cpu-after.txt",
                    "startup": {"amStartFile": "am.txt", "requestedMode": "COLD"}}]}
            path = root / "legacy-results.json"
            path.write_text(json.dumps(data))
            with patch("analyze.legacy_trace_summary", return_value={"status": "available", "main_thread_cpu": [{"cpu_ms": 3}]}):
                result = analyze.legacy_results_summary(path, Path("processor"))
                self.assertEqual(result["status"], "available")
                measured = result["iterations"][0]
                self.assertEqual(measured["gfxinfo"]["status"], "unavailable")
                self.assertEqual(measured["am_start"]["timings"]["am_start_TotalTime_ms"], 20)
                self.assertEqual(measured["atrace"]["main_thread_cpu"][0]["cpu_ms"], 3)
                self.assertEqual(measured["proc_cpu"]["processes"][capture.PACKAGE]["cpu_ms"], 120)
                data["iterations"][0]["startup"]["requestedMode"] = "WARM"
                path.write_text(json.dumps(data))
                self.assertEqual(analyze.legacy_results_summary(path, Path("processor"))["status"], "unavailable")
            self.assertEqual(result["composition_tracing"]["status"], "unavailable")

    def test_scheduler_integrity_errors_gate_cpu_without_rejecting_unrelated_metrics(self):
        for name in ("mismatched_sched_switch_tids", "sched_switch_out_of_order", "ftrace_cpu_has_data_loss"):
            rows = {"trace_health": [{"name": name, "value": 15, "severity": "error"}],
                    "cpu": [{"cpu_ms": 2}], "frames": [{"duration_ms": 3}]}
            analyze.gate_scheduler_cpu(rows, ("cpu",))
            self.assertEqual(rows["cpu"]["status"], "unavailable")
            self.assertEqual(rows["cpu"]["untrusted_estimates"], [{"cpu_ms": 2}])
            self.assertEqual(rows["frames"], [{"duration_ms": 3}])
        clean = {"trace_health": [], "cpu": [{"cpu_ms": 0}]}
        analyze.gate_scheduler_cpu(clean, ("cpu",))
        self.assertEqual(clean["cpu"], [{"cpu_ms": 0}])

    def test_legacy_scheduler_requires_consistent_retention_without_gating_native_or_independent_metrics(self):
        for retention in (None, analyze.unavailable("No kernel accounting header"),
                          {"status": "available", "reported_overwritten_entries": 0, "header_body_consistent": False}):
            with self.subTest(retention=retention):
                rows = {"trace_health": [], "cpu": [{"cpu_ms": 2}], "proc_cpu": {"cpu_ms": 3},
                        "gfxinfo": {"frames": 4}, "am_start": {"total_ms": 5}}
                if retention is not None:
                    rows["legacy_ftrace_retention"] = retention
                analyze.gate_scheduler_cpu(rows, ("cpu",), require_legacy_retention=True)
                self.assertEqual(rows["cpu"]["status"], "unavailable")
                self.assertEqual(rows["cpu"]["untrusted_estimates"], [{"cpu_ms": 2}])
                self.assertIn("missing_or_inconsistent_legacy_retention", rows["scheduler_cpu_warning"])
                self.assertEqual(rows["proc_cpu"], {"cpu_ms": 3})
                self.assertEqual(rows["gfxinfo"], {"frames": 4})
                self.assertEqual(rows["am_start"], {"total_ms": 5})
        native = {"trace_health": [], "cpu": [{"cpu_ms": 0}]}
        analyze.gate_scheduler_cpu(native, ("cpu",))
        self.assertEqual(native["cpu"], [{"cpu_ms": 0}])

    def test_explicit_legacy_backend_disables_perfetto_composition_tracing_on_api33(self):
        identity = {"state": "device", "model": "ONE A2003", "device": "oneplus2",
                    "product": "lineage_oneplus2", "api": "33"}

        def execute(argv, **kwargs):
            if "instrument" in argv:
                self.assertIn(["-e", "measurementBackend", "legacyAtrace"], [argv[i:i+3] for i in range(len(argv))])
                self.assertIn(["-e", "androidx.benchmark.fullTracing.enable", "false"], [argv[i:i+3] for i in range(len(argv))])
                kwargs["stdout"].write("OK (1 test)\nINSTRUMENTATION_CODE: -1\n")
            return SimpleNamespace(returncode=0, stdout="Success\n", stderr="")

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            config = root / "config.json"
            config.write_text(json.dumps({"anchorMillis": 1792195200000, "iterations": 1,
                                          "privileged": False, "monitoring": True}))
            apk = {"path": str(root / "profile.apk"), "sha256": "a" * 64}
            argv = ["run.py", "--adb", "adb", "--aapt", "aapt", "--serial", "oneplus",
                    "--app-apk", apk["path"], "--benchmark-apk", str(root / "macro.apk"),
                    "--config", str(config), "--output", str(root / "results"), "--method", "startupCold", "--legacy-atrace"]
            with patch("sys.argv", argv), patch("run.verify_device", return_value=identity), \
                    patch("run.snapshot", return_value={}), patch("run.validate_apk", return_value=apk), \
                    patch("run.subprocess.run", side_effect=execute):
                benchmark_run.main()
            result = json.loads((root / "results/run-result.json").read_text())
            self.assertEqual(result["measurementBackend"], "legacyAtrace")
            self.assertFalse(result["fullTracing"])
            with patch("sys.argv", argv), patch("run.verify_device", return_value={"api": "29"}), \
                    patch("run.validate_apk") as apk_validation, patch("sys.stderr"):
                with self.assertRaises(SystemExit) as denied:
                    benchmark_run.main()
                self.assertEqual(denied.exception.code, 2)
                apk_validation.assert_not_called()

    def test_capture_pulls_binary_separately_and_cleans_only_its_files(self):
        commands = []

        def read(adb, serial, *args):
            argv = capture.command(adb, serial, *args)
            commands.append(argv)
            if args[0] == "pull":
                Path(args[2]).write_bytes(b"synthetic trace bytes")
            return {"command": argv, "exit_code": 0, "stdout": "", "stderr": ""}

        def execute(argv, **kwargs):
            self.assertNotIn("input", kwargs)
            self.assertEqual(argv[5], "exec-out")
            self.assertEqual(len(argv), 7)
            self.assertRegex(argv[6], r"^cat /data/local/tmp/battery-monitor-profile-[0-9a-f]{32}\.pbtxt"
                                     r" \| perfetto --txt -c - -o /data/misc/perfetto-traces/"
                                     r"battery-monitor-profile-[0-9a-f]{32}\.pftrace$")
            kwargs["stdout"].write(b"perfetto command diagnostics only")
            return SimpleNamespace(returncode=0)

        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "capture"
            argv = ["capture.py", "--adb", "adb", "--serial", "phone", "--seconds", "1", "--output", str(output)]
            with patch("sys.argv", argv), patch("capture.verify_device", return_value={}), \
                    patch("capture.snapshot", return_value={}), patch("capture.read_device", side_effect=read), \
                    patch("capture.subprocess.run", side_effect=execute):
                capture.main()
            evidence = json.loads((output / "capture.json").read_text())
            self.assertEqual(evidence["status"], "captured")
            self.assertEqual(evidence["config_push"]["command"][5], "push")
            self.assertEqual(evidence["config_cleanup"]["command"][5:8], ["shell", "rm", "--"])
            self.assertEqual(evidence["config_push"]["command"][-1], evidence["config_cleanup"]["command"][-1])
            self.assertEqual(evidence["trace_pull"]["command"][-2], evidence["trace_cleanup"]["command"][-1])
            self.assertEqual((output / "system.perfetto-trace").read_bytes(), b"synthetic trace bytes")
            self.assertEqual((output / "perfetto.stdout.txt").read_bytes(), b"perfetto command diagnostics only")
            self.assertEqual(evidence["remote_retained"], [])
        for invalid in ("/data/local/tmp/production.pbtxt", "/data/local/tmp/battery-monitor-profile-*.pbtxt",
                        "/data/misc/perfetto-traces/*", "/data/misc/perfetto-traces/production.pftrace",
                        "/data/local/tmp/battery-monitor-profile-" + "a" * 32 + ".pbtxt;reboot"):
            with patch("capture.verify_device") as verify:
                with self.assertRaises(ValueError):
                    capture.cleanup_remote_file("adb", "phone", invalid)
                verify.assert_not_called()

    def test_capture_rejects_perfetto_stdout_error_even_when_exit_is_zero(self):
        text = b"\x1b[31m[123.456] perfetto_cmd.cc:340 Could not open config (errno:13 Permission denied)\x1b[0m\n"

        def execute(argv, **kwargs):
            kwargs["stdout"].write(text)
            return SimpleNamespace(returncode=0)

        def read(adb, serial, *args):
            if args[0] == "pull":
                Path(args[2]).write_bytes(b"partial trace")
            return {"exit_code": 0}

        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "capture"
            argv = ["capture.py", "--adb", "adb", "--serial", "phone", "--seconds", "1", "--output", str(output)]
            with patch("sys.argv", argv), patch("capture.verify_device", return_value={}), \
                    patch("capture.snapshot", return_value={}), \
                    patch("capture.read_device", side_effect=read), \
                    patch("capture.subprocess.run", side_effect=execute):
                with self.assertRaisesRegex(RuntimeError, "error diagnostics"):
                    capture.main()
            evidence = json.loads((output / "capture.json").read_text())
            self.assertEqual(evidence["status"], "unavailable")
            self.assertEqual(evidence["exit_code"], 0)
            self.assertEqual((output / "perfetto.stdout.txt").read_bytes(), text)
            self.assertEqual((output / "system.perfetto-trace").read_bytes(), b"partial trace")
            self.assertIn("config_cleanup", evidence)

    def test_capture_always_pulls_partial_trace_and_retains_remote_files_after_pull_failure(self):
        for failure in ("command", "timeout", "pull", "empty"):
            with self.subTest(failure=failure), tempfile.TemporaryDirectory() as directory:
                commands = []
                output = Path(directory) / "capture"

                def read(adb, serial, *args):
                    commands.append(args)
                    if args[0] == "pull":
                        Path(args[2]).write_bytes(b"" if failure == "empty" else b"partial trace")
                        return {"exit_code": 1 if failure == "pull" else 0}
                    return {"exit_code": 0}

                def execute(argv, **kwargs):
                    if failure == "timeout":
                        raise subprocess.TimeoutExpired(argv, kwargs["timeout"])
                    return SimpleNamespace(returncode=1 if failure == "command" else 0)

                argv = ["capture.py", "--adb", "adb", "--serial", "phone", "--seconds", "1", "--output", str(output)]
                with patch("sys.argv", argv), patch("capture.verify_device", return_value={}), \
                        patch("capture.snapshot", return_value={}), patch("capture.read_device", side_effect=read), \
                        patch("capture.subprocess.run", side_effect=execute):
                    with self.assertRaises((RuntimeError, subprocess.TimeoutExpired)):
                        capture.main()
                evidence = json.loads((output / "capture.json").read_text())
                self.assertEqual(evidence["status"], "unavailable")
                self.assertTrue(any(args[0] == "pull" for args in commands))
                self.assertTrue((output / "system.perfetto-trace").is_file())
                if failure == "pull":
                    self.assertEqual(evidence["remote_retained"], [evidence["remote_config"], evidence["remote_trace"]])
                    self.assertFalse(any(args[:2] == ("shell", "rm") for args in commands))
                else:
                    self.assertIn("config_cleanup", evidence)
                    self.assertIn("trace_cleanup", evidence)

    def test_empty_and_zero_duration_traces_are_unavailable_and_fail_cli(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            trace = root / "trace"
            trace.write_bytes(b"")
            with patch("analyze.subprocess.run") as native:
                empty = analyze.trace_summary(trace, Path("processor"))
                native.assert_not_called()
            self.assertEqual(empty["status"], "unavailable")
            self.assertIsNone(empty["trace_processor_exit_code"])
            trace.write_bytes(b"nonempty fixture with zero duration")
            metrics = {name: [] for name in ("processes", "cpu", "memory_counters", "frames", "gc_named_slices",
                                             "database_named_slices", "shell_process_candidates", "trace_health")}
            metrics["bounds"] = [{"start_ns": 0, "end_ns": 0, "duration_ms": 0}]
            csv = '"metric","data"\n' + "".join(f'"{name}","{json.dumps(value).encode().hex()}"\n'
                                                     for name, value in metrics.items())
            binary = root / "processor"
            binary.write_bytes(b"fixture")
            output = root / "analysis.json"
            argv = ["analyze.py", "--trace", str(trace), "--trace-processor", str(binary), "--output", str(output)]
            with patch("sys.argv", argv), patch("analyze.subprocess.run", side_effect=[
                SimpleNamespace(returncode=0, stdout=csv, stderr=""),
                SimpleNamespace(returncode=0, stdout="fixture version", stderr=""),
            ]):
                with self.assertRaises(SystemExit) as failed:
                    analyze.main()
                self.assertEqual(failed.exception.code, 1)
            result = json.loads(output.read_text())["traces"][0]
            self.assertEqual(result["status"], "unavailable")
            self.assertIn("No positive trace duration", result["reason"])

    def test_use_installed_verifies_code_hash_and_rejects_unknown_or_mismatched_code(self):
        digest = "a" * 64
        apk = {"package": capture.PACKAGE, "sha256": digest}
        path = f"/data/app/~~abc==/{capture.PACKAGE}-xyz==/base.apk"
        with patch("run.subprocess.run"):
            device = unittest.mock.Mock(side_effect=[
                SimpleNamespace(returncode=0, stdout=f"package:{path}\n"),
                SimpleNamespace(returncode=0, stdout=f"{digest}  {path}\n"),
            ])
            verified = benchmark_run.verify_installed_apk(device, apk, Path("unused"))
        self.assertEqual(verified["sha256"], digest)
        self.assertEqual([call.args for call in device.call_args_list],
                         [("shell", "pm", "path", capture.PACKAGE), ("shell", "sha256sum", path)])
        for located, checksum in ((f"package:{path}\n", "b" * 64),
                                  (f"package:{path}\npackage:/data/app/split.apk\n", digest),
                                  ("package:/data/app/unsafe;reboot/base.apk\n", digest), ("", digest)):
            device = unittest.mock.Mock(side_effect=[
                SimpleNamespace(returncode=0, stdout=located),
                SimpleNamespace(returncode=0, stdout=f"{checksum}  {path}\n"),
            ])
            with self.assertRaises(RuntimeError):
                benchmark_run.verify_installed_apk(device, apk, Path("unused"))

    def test_use_installed_can_hash_only_the_pulled_code_apk(self):
        contents = b"verified code APK"
        apk = {"package": capture.PACKAGE, "sha256": benchmark_run.hashlib.sha256(contents).hexdigest()}
        path = f"/data/app/{capture.PACKAGE}-1/base.apk"

        def device(*args, **kwargs):
            if args[:3] == ("shell", "pm", "path"):
                return SimpleNamespace(returncode=0, stdout=f"package:{path}\n")
            if args[0] == "pull":
                self.assertEqual(args[1], path)
                args[2].write_bytes(contents)
                return SimpleNamespace(returncode=0, stdout="")
            return SimpleNamespace(returncode=127, stdout="")

        with tempfile.TemporaryDirectory() as directory:
            verified = benchmark_run.verify_installed_apk(device, apk, Path(directory))
        self.assertEqual(verified["source"], "pulled code APK")

    def test_runner_rejects_invalid_config_and_unknown_methods(self):
        config = {"anchorMillis": 1792195200000, "iterations": 5, "privileged": False, "monitoring": True}
        self.assertEqual(benchmark_run.validate_config(config), config)
        for invalid in ({**config, "iterations": True}, {**config, "iterations": 0},
                        {**config, "anchorMillis": 1.2}, {**config, "privileged": "false"},
                        {**config, "extra": "anything"}):
            with self.assertRaises(ValueError):
                benchmark_run.validate_config(invalid)
        source = "@Test\nfun startupCold() = Unit\nprivate fun notATest() = Unit"
        self.assertEqual(benchmark_run.validate_method("startupCold", source), "startupCold")
        for method in ("notATest", "startupCold;reboot", "startupCold#ignored"):
            with self.assertRaises(ValueError):
                benchmark_run.validate_method(method, source)

    def test_runner_detects_failures_with_successful_adb_exit_code(self):
        self.assertTrue(benchmark_run.instrumentation_passed(0, "OK (1 test)\nINSTRUMENTATION_CODE: -1\n"))
        for text in ("FAILURES!!!\nTests run: 1, Failures: 1", "INSTRUMENTATION_RESULT: shortMsg=Process crashed.",
                     "INSTRUMENTATION_STATUS_CODE: -2\nOK (1 test)", "OK (0 tests)", "INSTRUMENTATION_CODE: -1"):
            self.assertFalse(benchmark_run.instrumentation_passed(0, text))
        self.assertFalse(benchmark_run.instrumentation_passed(1, "OK (2 tests)"))

    def test_shizuku_options_are_scoped_and_do_not_enable_observational_stages(self):
        options = benchmark_run.shizuku_arguments
        self.assertEqual(options("startupCold", None, False, False, False, False), {})
        self.assertEqual(options("shizukuStatus", None, False, False, True, True), {
            "shizukuPhase": "status", "shizukuEnable": "false", "shizukuReaderChecks": "false"})
        self.assertEqual(options("shizukuMonitoringAcquisition", "warmup-01", True, True, True, True), {
            "shizukuPhase": "warmup-01", "shizukuEnable": "true", "shizukuReaderChecks": "true"})
        for arguments in ((None, "phase", False, False, True, True),
                          ("startupCold", None, True, False, True, True),
                          ("shizukuStatus", "../bad", False, False, True, True),
                          ("shizukuStatus", "a" * 49, False, False, True, True),
                          ("shizukuStatus", "", False, False, True, True),
                          ("shizukuStatus", None, False, False, True, False),
                          ("shizukuStatus", None, True, False, True, True),
                          ("shizukuStatus", None, False, True, True, True),
                          ("shizukuMonitoringAcquisition", None, False, False, False, True)):
            with self.subTest(arguments=arguments), self.assertRaises(ValueError):
                options(*arguments)

    def test_shizuku_runner_records_status_delivery_and_preserves_real_acquisition_failure(self):
        identity = {"state": "device", "model": "ONE A2003", "device": "oneplus2",
                    "product": "lineage_oneplus2", "api": "33"}
        config = {"anchorMillis": 1792195200000, "iterations": 5, "privileged": True, "monitoring": True}
        for method, passed in (("shizukuStatus", True), ("shizukuMonitoringAcquisition", False)):
            with self.subTest(method=method), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                config_path = root / "config.json"
                config_path.write_text(json.dumps(config))
                output = root / "results"
                apk = {"path": str(root / "profile.apk"), "sha256": "a" * 64}
                argv = ["run.py", "--adb", "adb", "--aapt", "aapt", "--serial", "oneplus",
                        "--app-apk", apk["path"], "--benchmark-apk", str(root / "macro.apk"),
                        "--config", str(config_path), "--output", str(output), "--method", method,
                        "--legacy-atrace", "--shizuku-phase", "revoked-01"]
                if not passed:
                    argv += ["--shizuku-enable", "--shizuku-reader-checks"]

                def execute(command, **kwargs):
                    if "instrument" in command:
                        kwargs["stdout"].write("OK (1 test)\n" if passed else
                                               "INSTRUMENTATION_STATUS_CODE: -2\nFAILURES!!!\n")
                    return SimpleNamespace(returncode=0, stdout="Success\n", stderr="")

                with patch("sys.argv", argv), patch("run.verify_device", return_value=identity), \
                        patch("run.snapshot", return_value={}), patch("run.validate_apk", return_value=apk), \
                        patch("run.subprocess.run", side_effect=execute):
                    if passed:
                        benchmark_run.main()
                    else:
                        with self.assertRaises(SystemExit) as failure:
                            benchmark_run.main()
                        self.assertEqual(failure.exception.code, 1)
                result = json.loads((output / "run-result.json").read_text())
                self.assertEqual(result["passed"], passed)
                self.assertFalse(result["fullTracing"])
                probe = result["probe"]
                self.assertEqual(probe["probeStatusOnly"], passed)
                self.assertTrue(probe["noATraceCaptured"])
                self.assertFalse(probe["fixtureResetRequested"])
                self.assertNotIn("available", probe)
                command = result["instrumentation_command"]
                for key, value in probe["instrumentationArguments"].items():
                    index = command.index(key)
                    self.assertEqual(command[index - 1:index + 2], ["-e", key, value])

    def test_runner_refuses_production_package_and_shared_uid(self):
        def result(text):
            return type("Result", (), {"stdout": text})()
        with patch("run.subprocess.run", return_value=result("package: name='codes.swistak.batterymonitor'")):
            with self.assertRaisesRegex(ValueError, "expected package"):
                benchmark_run.validate_apk("aapt", Path("fixture.apk"), capture.PACKAGE)
        with patch("run.subprocess.run", side_effect=[
            result(f"package: name='{capture.PACKAGE}'"), result('A: android:sharedUserId="uid"')
        ]):
            with self.assertRaisesRegex(ValueError, "sharedUserId"):
                benchmark_run.validate_apk("aapt", Path("fixture.apk"), capture.PACKAGE)
        with patch("run.subprocess.run", side_effect=[
            result(f"package: name='{capture.PACKAGE}'"),
            result('A: android:process(0x01010011)="codes.swistak.batterymonitor.BIS"')
        ]):
            with self.assertRaisesRegex(ValueError, "fixed process"):
                benchmark_run.validate_apk("aapt", Path("fixture.apk"), capture.PACKAGE)

    def test_redmi_shell_install_run_retains_results_and_targets_every_command(self):
        identity = {"state": "device", "model": "Redmi 7A", "device": "pine", "product": "pine_eea", "api": "29"}
        config = {"anchorMillis": 1792195200000, "iterations": 1, "privileged": False, "monitoring": True}
        commands = []

        def execute(argv, **kwargs):
            commands.append(argv)
            if "instrument" in argv:
                kwargs["stdout"].write("OK (1 test)\nINSTRUMENTATION_CODE: -1\n")
            return SimpleNamespace(returncode=0, stdout="Success\n", stderr="")

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            config_path = root / "config.json"
            config_path.write_text(json.dumps(config))
            output = root / "results"
            apk = {"path": str(root / "profile.apk"), "sha256": "a" * 64}
            argv = ["run.py", "--adb", "adb", "--aapt", "aapt", "--serial", "redmi",
                    "--app-apk", apk["path"], "--benchmark-apk", str(root / "macro.apk"),
                    "--config", str(config_path), "--output", str(output), "--method", "startupCold"]
            with patch("sys.argv", argv), patch("run.verify_device", return_value=identity), \
                    patch("run.snapshot", return_value={"fixture": True}), \
                    patch("run.validate_apk", return_value=apk), patch("run.subprocess.run", side_effect=execute):
                benchmark_run.main()
            result = json.loads((output / "run-result.json").read_text())
            self.assertTrue(result["passed"])
            self.assertFalse(result["fullTracing"])
            self.assertTrue((output / "conditions-before.json").is_file())
            self.assertTrue((output / "conditions-after.json").is_file())
            self.assertTrue(all(c[3:5] == ["-s", "redmi"] for c in commands))
            self.assertTrue(any(c[5:8] == ["shell", "pm", "install"] for c in commands))
            self.assertTrue(any(c[5] == "push" for c in commands))
            self.assertTrue(any(c[5] == "pull" for c in commands))
            self.assertFalse(any(c[5] == "install" for c in commands))
            self.assertFalse(any("uninstall" in c or "clear" in c for c in commands))

    def test_snapshot_retains_requested_stayawake_and_bounded_power_state(self):
        def read(adb, serial, *args):
            output = "2" if args[-1] == "stay_on_while_plugged_in" else ""
            if args[-1] == "power":
                output = "Power Manager State:\n  mWakefulness=Awake\n  mStayOn=true\n  unrelated=private\n"
            return {"exit_code": 0, "stdout": output}
        with patch("capture.verify_device", return_value={}), patch("capture.read_device", side_effect=read):
            observations = capture.snapshot("adb", "phone")["observations"]
        self.assertEqual(observations["stay_on_while_plugged_in"]["stdout"], "2")
        self.assertEqual(observations["power"]["stdout"], "mWakefulness=Awake\nmStayOn=true")

    def test_runner_timeout_stops_only_instrumentation_and_collects_partial_evidence(self):
        self.assertEqual(benchmark_run.CLASS_TIMEOUT_SECONDS, 7200)
        identity = {"state": "device", "model": "ONE A2003", "device": "oneplus2",
                    "product": "lineage_oneplus2", "api": "33"}
        config = {"anchorMillis": 1792195200000, "iterations": 1, "privileged": False, "monitoring": True}
        commands = []

        def execute(argv, **kwargs):
            commands.append(argv)
            if "instrument" in argv:
                self.assertEqual(kwargs["timeout"], benchmark_run.METHOD_TIMEOUT_SECONDS)
                kwargs["stdout"].write("INSTRUMENTATION_STATUS: preflight=running\n")
                raise subprocess.TimeoutExpired(argv, kwargs["timeout"])
            return SimpleNamespace(returncode=0, stdout="Success\n", stderr="")

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            config_path = root / "config.json"
            config_path.write_text(json.dumps(config))
            output = root / "results"
            apk = {"path": str(root / "profile.apk"), "sha256": "a" * 64}
            argv = ["run.py", "--adb", "adb", "--aapt", "aapt", "--serial", "oneplus",
                    "--app-apk", apk["path"], "--benchmark-apk", str(root / "macro.apk"),
                    "--config", str(config_path), "--output", str(output), "--method", "startupCold"]
            with patch("sys.argv", argv), patch("run.verify_device", return_value=identity), \
                    patch("run.snapshot", return_value={"fixture": True}), \
                    patch("run.validate_apk", return_value=apk), patch("run.subprocess.run", side_effect=execute):
                with self.assertRaises(SystemExit) as stopped:
                    benchmark_run.main()
                self.assertEqual(stopped.exception.code, 1)
            result = json.loads((output / "run-result.json").read_text())
            self.assertTrue(result["timed_out"])
            self.assertEqual(result["timeout_seconds"], 900)
            self.assertEqual(result["status"], "unavailable")
            self.assertIsNone(result["instrumentation_exit_code"])
            self.assertFalse(result["passed"])
            stop_commands = [c for c in commands if "force-stop" in c]
            self.assertEqual(stop_commands, [["rtk", "proxy", "adb", "-s", "oneplus", "shell", "am",
                                             "force-stop", benchmark_run.BENCHMARK_PACKAGE]])
            self.assertTrue((output / "conditions-after.json").is_file())
            self.assertIn("preflight", (output / "instrumentation.txt").read_text())
            self.assertTrue(any(c[5] == "pull" for c in commands))

    def test_runner_distinguishes_queried_and_owned_provider_authorities(self):
        manifest = ("E: manifest\n"
                    "  E: queries\n"
                    "    E: provider\n"
                    '      A: android:authorities(0x01010018)="lineagesettings"\n'
                    "  E: application\n"
                    "    E: provider\n"
                    f'      A: android:authorities(0x01010018)="{capture.PACKAGE}.shizuku"\n')
        badging = SimpleNamespace(stdout=f"package: name='{capture.PACKAGE}'")
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "app.apk"
            apk.write_bytes(b"fixture")
            with patch("run.subprocess.run", side_effect=[badging, SimpleNamespace(stdout=manifest)]):
                self.assertEqual(benchmark_run.validate_apk("aapt", apk, capture.PACKAGE)["package"], capture.PACKAGE)
            invalid = manifest.replace(f"{capture.PACKAGE}.shizuku", "codes.swistak.batterymonitor.shizuku")
            with patch("run.subprocess.run", side_effect=[badging, SimpleNamespace(stdout=invalid)]):
                with self.assertRaisesRegex(ValueError, "provider authority"):
                    benchmark_run.validate_apk("aapt", apk, capture.PACKAGE)

    def test_explicit_serial_and_wrong_device_rejection(self):
        self.assertEqual(capture.command("adb", "phone-2", "shell", "true"),
                         ["rtk", "proxy", "adb", "-s", "phone-2", "shell", "true"])
        with patch("capture.read_device", side_effect=[
            {"exit_code": 0, "stdout": value} for value in
            ("device", "Pixel 8", "husky", "husky", "34")
        ]) as read:
            with self.assertRaisesRegex(RuntimeError, "different or unauthorized"):
                capture.verify_device("adb", "phone-2")
        self.assertTrue(all(call.args[1] == "phone-2" for call in read.call_args_list))
        self.assertTrue(capture.is_supported_device({"model": "ONE A2003", "device": "oneplus2",
                                                     "product": "lineage_oneplus2", "api": "33"}))
        self.assertTrue(capture.is_supported_device({"model": "Redmi 7A", "device": "pine",
                                                     "product": "pine_eea", "api": "29"}))
        self.assertFalse(capture.is_supported_device({"model": "Redmi 7A", "device": "pine",
                                                      "product": "pine_eea", "api": "33"}))

    def test_config_covers_both_processes_and_required_sources(self):
        config = capture.config(17)
        for process in capture.PROCESSES:
            self.assertIn(f'atrace_apps: "{process}"', config)
        for source in ("sched_process_exec", "sched_process_fork", "cpu_frequency", "cpu_idle",
                       'atrace_categories: "database"', 'atrace_categories: "dalvik"',
                       'name: "linux.process_stats"', "frametimeline", "duration_ms: 17000"):
            self.assertIn(source, config)

    def test_distribution_keeps_real_zero_and_rejects_missing_samples(self):
        self.assertEqual(analyze.distribution([0, 10])["p50"], 5)
        self.assertEqual(analyze.distribution([False, None, float("nan")])["status"], "unavailable")

    def test_benchmark_iterations_and_sampled_percentiles(self):
        fixture = {"benchmarks": [{"name": "cold", "metrics": {
            "timeToInitialDisplayMs": {"runs": [0, 10]}}, "sampledMetrics": {
            "frameDurationCpuMs": {"runs": [[1, 3], [5]]}}}]}
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "benchmark.json"
            path.write_text(json.dumps(fixture))
            metrics = analyze.benchmark_summary(path)["benchmarks"][0]["metrics"]
        self.assertEqual(metrics["timeToInitialDisplayMs"]["iterations"], 2)
        self.assertEqual(metrics["timeToInitialDisplayMs"]["pooled_samples"]["min"], 0)
        self.assertEqual(metrics["frameDurationCpuMs"]["pooled_samples"]["p50"], 3)

    def test_missing_frame_data_and_unknown_jank_are_unavailable(self):
        self.assertEqual(analyze.summarize_frames([])["status"], "unavailable")
        frames = [{"process": capture.PACKAGE, "duration_ms": 12, "jank_type": label}
                  for label in (None, "", "Unknown")]
        summary = analyze.summarize_frames(frames)[capture.PACKAGE]
        self.assertEqual(summary["janky_surface_frame_percent"]["status"], "unavailable")
        frames += [{"process": capture.PACKAGE, "duration_ms": 24, "jank_type": "None"},
                   {"process": capture.PACKAGE, "duration_ms": 40, "jank_type": "App Deadline Missed"}]
        self.assertEqual(analyze.summarize_frames(frames)[capture.PACKAGE]["janky_surface_frame_percent"], 50)

    def test_trace_hex_output_handles_arbitrary_slice_text(self):
        value = [{"name": 'query "value", newline\nwith UTF-8: Ś'}]
        encoded = json.dumps(value).encode().hex()
        self.assertEqual(analyze.parse_trace_output(f'"metric","data"\n"slices","{encoded}"\n'),
                         {"slices": value})

    def test_empty_trace_never_becomes_zero_work(self):
        metric_names = ("bounds", "processes", "cpu", "memory_counters", "frames", "gc_named_slices",
                        "database_named_slices", "shell_process_candidates", "trace_health")
        output = '"metric","data"\n' + "".join(f'"{name}","5b5d"\n' for name in metric_names)
        result = type("Result", (), {"returncode": 0, "stdout": output, "stderr": ""})()
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "trace"
            path.write_bytes(b"fixture")
            with patch("analyze.subprocess.run", return_value=result):
                summary = analyze.trace_summary(path, Path("trace_processor"))
        for metric in ("cpu", "memory_counters", "gc_named_slices", "database_named_slices",
                       "shell_process_candidates", "exact_root_command_count", "exact_database_query_count"):
            self.assertEqual(summary[metric]["status"], "unavailable")


if __name__ == "__main__":
    unittest.main()
