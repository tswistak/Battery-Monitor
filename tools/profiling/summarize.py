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

"""Aggregate existing legacy analysis JSON; no device access or trace reprocessing."""

import argparse
from collections import defaultdict
import json
from pathlib import Path

from analyze import distribution, unavailable
from capture import PROCESSES


def provenance(result):
    source = Path(result["path"])
    for root in source.parents[:3]:
        if not (root / "run-result.json").is_file():
            continue
        run = json.loads((root / "run-result.json").read_text())
        config = json.loads((root / "run-config.json").read_text())
        apks = json.loads((root / "apks.json").read_text())
        return {"run": str(root), "serial": run.get("serial"), "config": config,
                "measurementBackend": run.get("measurementBackend"),
                "apk_sha256": {apk["package"]: apk["sha256"] for apk in apks},
                "condition_artifacts": [str(root / name) for name in ("conditions-before.json", "conditions-after.json")
                                        if (root / name).is_file()]}
    return unavailable("Run provenance not found beside the source legacy JSON")


def aggregate(paths):
    grouped = defaultdict(list)
    seen = set()
    for path in paths:
        data = json.loads(path.read_text())
        if not isinstance(data.get("legacy_results"), list):
            raise ValueError(f"Expected analyzer JSON with legacy_results: {path}")
        for result in data["legacy_results"]:
            if result["path"] in seen:
                raise ValueError(f"Duplicate legacy source would double-count iterations: {result['path']}")
            seen.add(result["path"])
            grouped[result["scenario"]].append((path, result))
    if paths and not grouped:
        raise ValueError("Inputs contain no legacy scenario results")
    summaries = {}
    for scenario, inputs in grouped.items():
        all_iterations = [item for _, result in inputs for item in result["iterations"]]
        valid = [item for item in all_iterations if item["status"] == "available"]
        samples = defaultdict(list)
        observed = defaultdict(int)
        counter_rates = set()
        counter_bases = defaultdict(set)

        def add(name, value):
            if type(value) in (int, float):
                samples[name].append(value)

        for item in valid:
            for key, value in item.get("am_start", {}).get("timings", {}).items():
                if item["am_start"].get("status") == "available":
                    add(key, value)
            gfx = item["gfxinfo"]
            eligible = [frame["frame_completed_minus_intended_vsync_ms"]
                        for frame in gfx.get("retained_frames", []) if not frame["exclusions"]]
            samples["gfxinfo_retained_FrameCompleted_minus_IntendedVsync_ms"].extend(eligible)
            observed["gfxinfo_eligible_retained_rows"] += len(eligible)
            observed["gfxinfo_excluded_retained_rows"] += gfx.get("excluded_frame_count", 0)
            jank = gfx.get("android_reported_jank", {})
            if type(gfx.get("android_reported_total_frames")) is int and gfx["android_reported_total_frames"] > 0:
                add("gfxinfo_android_reported_janky_frame_percent_per_iteration", jank.get("percent"))
            trace = item["atrace"]
            observed["iterations_with_nonzero_trace_health"] += bool(trace.get("trace_health"))
            observed["iterations_with_scheduler_cpu_gate"] += "scheduler_cpu_warning" in trace
            observed["iterations_with_complete_tgid_mapping"] += trace.get("tgid_mapping", {}).get("complete", False)
            for process in PROCESSES:
                rows = trace.get("main_thread_cpu")
                if isinstance(rows, list):
                    matches = [row["cpu_ms"] for row in rows if row["process"] == process]
                    if matches:
                        add(f"scheduler_main_thread_cpu_ms/{process}", sum(matches))
                proc = item.get("proc_cpu", {}).get("processes", {}).get(process, {})
                if proc.get("status") == "available":
                    counter_rates.add(proc["clock_ticks_per_second"])
                    counter_bases[process].add(proc["basis"])
                    for field in ("cpu_ms", "user_cpu_ms", "system_cpu_ms"):
                        add(f"proc_{field}/{process}", proc.get(field))
                    for phase in ("before", "after"):
                        for field in ("vsize_bytes", "rss_pages"):
                            add(f"proc_{field}_{phase}/{process}", proc.get(f"memory_{phase}", {}).get(field))
        names = {"am_start_ThisTime_ms", "am_start_TotalTime_ms", "am_start_WaitTime_ms",
                 "gfxinfo_retained_FrameCompleted_minus_IntendedVsync_ms",
                 "gfxinfo_android_reported_janky_frame_percent_per_iteration"}
        for process in PROCESSES:
            names.add(f"scheduler_main_thread_cpu_ms/{process}")
            names.update(f"proc_{field}/{process}" for field in ("cpu_ms", "user_cpu_ms", "system_cpu_ms"))
            names.update(f"proc_{field}_{phase}/{process}" for field in ("vsize_bytes", "rss_pages") for phase in ("before", "after"))
        memory_names = [name for name in sorted(names) if name.startswith(("proc_vsize_", "proc_rss_")) and samples[name]]
        summaries[scenario] = {
            "status": ("partial" if len(valid) < len(all_iterations) else "available") if valid else "unavailable",
            "valid_iterations": len(valid),
            "recorded_iterations": len(all_iterations), "excluded_failed_iterations": len(all_iterations) - len(valid),
            "metrics": {name: distribution(samples[name]) for name in sorted(names)}, "coverage": dict(observed),
            "proc_cpu_clock_ticks_per_second": sorted(counter_rates),
            "proc_cpu_basis": {process: sorted(values) for process, values in counter_bases.items()},
            "memory": ({"status": "available", "metrics": memory_names,
                        "definition": "Paired stat field 23 virtual bytes and field 24 resident pages before/after, with CPU's PID/starttime guard; no assumed page size; not peak, heap, or allocations"}
                       if memory_names else unavailable("No valid paired process stat memory observations; condition artifacts contain supporting snapshots outside the timed window")),
            "sources": [{"analysis": str(path), "legacy_json": result["path"], "sha256": result["sha256"],
                         "provenance": provenance(result)} for path, result in inputs]}
    return summaries


def comparison_warnings(before, after):
    warnings = []
    if before["valid_iterations"] != after["valid_iterations"]:
        warnings.append("Valid iteration counts differ")
    records = [source["provenance"] for side in (before, after) for source in side["sources"]]
    if any(record.get("status") == "unavailable" for record in records):
        warnings.append("Run provenance is unavailable; comparison conditions cannot be established")
    else:
        for key in ("serial", "config", "measurementBackend"):
            if len({json.dumps(record.get(key), sort_keys=True) for record in records}) != 1:
                warnings.append(f"Run {key} differs")
        macro = "codes.swistak.batterymonitor.macrobenchmark"
        hashes = {record["apk_sha256"].get(macro) for record in records}
        if None in hashes:
            warnings.append("Benchmark harness APK hash is unavailable")
        elif len(hashes) != 1:
            warnings.append("Benchmark harness APK hashes differ; instrumentation overhead or scenario behavior can differ")
    warnings.append("Review thermal, compilation, locale, font, animation, power and scenario settings in condition artifacts")
    return warnings


def compare(before, after):
    result = {}
    for scenario in sorted(before.keys() | after.keys()):
        first, last = before.get(scenario), after.get(scenario)
        entry = {"before": first or unavailable("Scenario absent before"),
                 "after": last or unavailable("Scenario absent after")}
        if first and last and first["valid_iterations"] and last["valid_iterations"]:
            entry["comparison_warnings"] = comparison_warnings(first, last)
            changes = {}
            for name, metric in first["metrics"].items():
                other = last["metrics"].get(name, {})
                if metric.get("status") == other.get("status") == "available":
                    delta = other["p50"] - metric["p50"]
                    changes[name] = {"p50_change": delta, "p95_change": other["p95"] - metric["p95"],
                                     "p50_percent_change": 100 * delta / metric["p50"] if metric["p50"] != 0
                                     else unavailable("Before median is zero; percentage change undefined")}
            entry["changes"] = changes
        result[scenario] = entry
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--before", nargs="+", required=True, type=Path, help="Existing analyzer JSON files")
    parser.add_argument("--after", nargs="+", default=[], type=Path, help="Optional corresponding analyzer JSON files")
    parser.add_argument("--output", required=True, type=Path, help="New summary JSON")
    args = parser.parse_args()
    before, after = aggregate(args.before), aggregate(args.after)
    report = {"schemaVersion": 1, "measurementBackend": "legacyAtrace", "scenarios": compare(before, after),
              "definitions": ["Only completed, analyzer-valid iterations contribute; failed partial rows are excluded",
                              "AM fields are native am start -W timings, not TTID",
                              "gfxinfo percentiles pool eligible retained ring rows; Android jank percentages remain per-iteration aggregate samples",
                              "Jank p50/p95 changes use percentage points; p50_percent_change always denotes relative change",
                              "Proc CPU uses paired process counters; scheduler CPU uses only trusted observed-PID main-thread records",
                              "Stat memory is before/after virtual bytes and raw resident pages with the same process identity guard; not peak, heap, or allocation profiling",
                              "Before/after deltas describe retained samples; warnings and measurement scope bound interpretation"]}
    with args.output.open("x") as output:
        output.write(json.dumps(report, indent=2, allow_nan=False) + "\n")


if __name__ == "__main__":
    main()
