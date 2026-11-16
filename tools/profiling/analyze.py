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

"""Summarize local Macrobenchmark, legacy ATrace/gfxinfo, and Perfetto evidence."""

import argparse
import csv
from decimal import Decimal
import hashlib
import io
import json
import math
from pathlib import Path
import re
import statistics
import subprocess

from capture import PROCESSES
from analysis_binder import binder_transactions_summary


def unavailable(reason):
    return {"status": "unavailable", "reason": reason}


def distribution(values):
    values = sorted(value for value in values
                    if isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value))
    if not values:
        return unavailable("No finite numeric samples")
    result = {"status": "available", "count": len(values), "min": values[0],
              "max": values[-1], "mean": statistics.mean(values)}
    for percentile in (50, 90, 95, 99):
        index = (len(values) - 1) * percentile / 100
        lower = math.floor(index)
        upper = math.ceil(index)
        result[f"p{percentile}"] = values[lower] + (values[upper] - values[lower]) * (index - lower)
    return result


def benchmark_summary(path):
    data = json.loads(path.read_text())
    summaries = []
    for benchmark in data.get("benchmarks", []):
        metrics = {}
        for kind in ("metrics", "sampledMetrics"):
            for name, metric in benchmark.get(kind, {}).items():
                runs = metric.get("runs", [])
                values = [value for run in runs for value in (run if isinstance(run, list) else [run])]
                metrics[name] = {"kind": kind, "iterations": len(runs), "reported": metric,
                                 "pooled_samples": distribution(values)}
        summaries.append({"name": benchmark.get("name"), "class_name": benchmark.get("className"),
                          "metrics": metrics or unavailable("No benchmark metrics")})
    return {"path": str(path.resolve()), "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
            "context": data.get("context"), "benchmarks": summaries or unavailable("No benchmarks")}


def trace_sql():
    targets = ", ".join(f"'{name}'" for name in PROCESSES)
    return f"""
WITH RECURSIVE descendants(upid) AS (
  SELECT upid FROM process WHERE name IN ({targets})
  UNION SELECT p.upid FROM process p JOIN descendants d ON p.parent_upid = d.upid
), target_slices AS (
  SELECT p.name AS process_name, t.tid, p.pid, s.name, s.dur
  FROM slice s JOIN thread_track tt ON tt.id = s.track_id
  JOIN thread t ON t.utid = tt.utid JOIN process p ON p.upid = t.upid
  WHERE p.name IN ({targets}) AND s.dur >= 0
)
SELECT 'bounds' AS metric, hex(json_group_array(json_object('start_ns', start_ts,
  'end_ns', end_ts, 'duration_ms', (end_ts - start_ts) / 1e6))) AS data FROM trace_bounds
UNION ALL
SELECT 'processes', hex(json_group_array(json_object('name', name, 'pid', pid, 'upid', upid,
  'uid', uid, 'start_ns', start_ts, 'end_ns', end_ts, 'cmdline', cmdline)))
FROM process WHERE name IN ({targets})
UNION ALL
SELECT 'cpu', hex(json_group_array(json_object('process', name, 'pid', pid,
  'cpu_ms', cpu_ms, 'main_thread_cpu_ms', main_ms))) FROM (
  SELECT p.name, p.pid, SUM(s.dur) / 1e6 AS cpu_ms,
    SUM(CASE WHEN t.tid = p.pid THEN s.dur ELSE 0 END) / 1e6 AS main_ms
  FROM sched s JOIN thread t ON t.utid = s.utid JOIN process p ON p.upid = t.upid
  WHERE p.name IN ({targets}) AND s.dur > 0 GROUP BY p.upid
)
UNION ALL
SELECT 'memory_counters', hex(json_group_array(json_object('process', name, 'counter', counter,
  'unit', unit, 'samples', samples, 'min', minimum, 'max', maximum, 'mean', mean))) FROM (
  SELECT p.name, ct.name AS counter, ct.unit, COUNT(*) AS samples,
    MIN(c.value) AS minimum, MAX(c.value) AS maximum, AVG(c.value) AS mean
  FROM counter c JOIN process_counter_track ct ON ct.id = c.track_id
  JOIN process p ON p.upid = ct.upid
  WHERE p.name IN ({targets}) AND ct.name GLOB 'mem.*' GROUP BY p.upid, ct.id
)
UNION ALL
SELECT 'frames', hex(json_group_array(json_object('process', p.name, 'duration_ms', f.dur / 1e6,
  'jank_type', f.jank_type, 'surface_frame_token', f.surface_frame_token,
  'layer', f.layer_name)))
FROM actual_frame_timeline_slice f JOIN process p ON p.upid = f.upid
WHERE p.name IN ({targets}) AND f.dur >= 0 AND f.surface_frame_token IS NOT NULL
UNION ALL
SELECT 'gc_named_slices', hex(json_group_array(json_object('process', process_name, 'name', name,
  'count', count, 'total_ms', total_ms, 'max_ms', max_ms))) FROM (
  SELECT process_name, name, COUNT(*) AS count, SUM(dur) / 1e6 AS total_ms,
    MAX(dur) / 1e6 AS max_ms FROM target_slices
  WHERE name GLOB '*GC*' OR LOWER(name) LIKE '%garbage collect%'
  GROUP BY process_name, name
)
UNION ALL
SELECT 'database_named_slices', hex(json_group_array(json_object('process', process_name,
  'name', name, 'count', count, 'total_ms', total_ms, 'main_thread_count', main_count))) FROM (
  SELECT process_name, name, COUNT(*) AS count, SUM(dur) / 1e6 AS total_ms,
    SUM(tid = pid) AS main_count FROM target_slices
  WHERE LOWER(name) LIKE '%sqlite%' OR LOWER(name) LIKE 'query%' OR LOWER(name) LIKE 'execute%'
    OR LOWER(name) LIKE 'prepare%' OR LOWER(name) LIKE '%database%'
  GROUP BY process_name, name
)
UNION ALL
SELECT 'shell_process_candidates', hex(json_group_array(json_object('name', p.name, 'pid', p.pid,
  'uid', p.uid, 'cmdline', p.cmdline, 'parent_upid', p.parent_upid,
  'attributed_descendant', p.upid IN descendants,
  'start_ns', p.start_ts, 'end_ns', p.end_ts,
  'complete_lifetime_ms', CASE WHEN p.end_ts >= p.start_ts THEN (p.end_ts - p.start_ts) / 1e6 END)))
FROM process p
WHERE (p.upid IN descendants OR p.uid = 0)
  AND (p.name IN ('su', 'sh', 'bash', 'toybox', 'busybox')
    OR p.cmdline GLOB '/system/bin/sh*' OR p.cmdline GLOB '/system/bin/su*')
UNION ALL
SELECT 'trace_health', hex(json_group_array(json_object('name', name, 'value', value,
  'severity', severity, 'source', source))) FROM stats WHERE value != 0 AND severity != 'info'
""".strip()


def parse_trace_output(output):
    rows = csv.DictReader(io.StringIO(output))
    return {row["metric"]: json.loads(bytes.fromhex(row["data"]).decode()) for row in rows}


def summarize_frames(frames):
    if not frames:
        return unavailable("No app FrameTimeline slices; Android 12+ and ROM support required")
    result = {}
    for process in sorted({frame["process"] for frame in frames}):
        selected = [frame for frame in frames if frame["process"] == process]
        known = [frame for frame in selected if frame["jank_type"] not in (None, "", "Unknown")]
        result[process] = {
            "surface_frame_duration_ms": distribution([frame["duration_ms"] for frame in selected]),
            "jank_known_frames": len(known),
            "jank_unknown_frames": len(selected) - len(known),
            "janky_surface_frame_percent": (100 * sum(frame["jank_type"] != "None" for frame in known)
                                              / len(known)) if known else unavailable("No jank labels"),
            "definition": "FrameTimeline surface frames, including multiple surfaces; not CPU frame duration",
        }
    return result


def legacy_ftrace_retention(raw_lines):
    headers = [match for line in raw_lines if (match := re.match(
        r"^# entries-in-buffer/entries-written:\s*(\d+)/(\d+)\s+#P:\d+\s*$", line))]
    if len(headers) != 1:
        return unavailable("Expected one legacy ftrace buffer accounting header")
    retained, written = map(int, headers[0].groups())
    event_lines = sum(bool(re.match(r"^\s*.+-\d+\s+(?:\([^)]*\)\s+)?\[\d+\].*?\s\d+\.\d+:\s+\S+", line))
                      for line in raw_lines)
    consistent = retained == event_lines and written >= retained
    loss = max(0, written - retained)
    classification = "reported_overwrite" if loss else "no_reported_overwrite"
    if not consistent:
        classification = ("reported_overwrite_with_header_body_inconsistency" if loss
                          else "header_body_inconsistency")
    return {"status": "available", "entries_in_buffer": retained, "entries_written": written,
            "reported_overwritten_entries": loss, "parsed_event_lines": event_lines,
            "header_body_consistent": consistent, "classification": classification,
            "scope": "Capture-wide kernel header; a positive difference cannot establish an unaffected action window. "
                     "Header/body inconsistency does not establish its cause or locate missing events."}


def gate_scheduler_cpu(rows, metrics, require_legacy_retention=False):
    names = {"mismatched_sched_switch_tids", "sched_switch_out_of_order", "invalid_cpu_times",
             "task_state_invalid", "systrace_parse_failure"}
    errors = [item for item in rows["trace_health"]
              if item["severity"].replace(" ", "_") in ("error", "data_loss")
              and (item["name"] in names or item["name"].startswith("ftrace_"))]
    retention = rows.get("legacy_ftrace_retention", {})
    reported_loss = retention.get("reported_overwritten_entries", 0) > 0
    missing_or_inconsistent_retention = require_legacy_retention and (
        retention.get("status") != "available" or retention.get("header_body_consistent") is not True)
    if not errors and not reported_loss and not missing_or_inconsistent_retention:
        return
    reason = "Scheduler integrity errors, reported ftrace buffer loss, or missing/inconsistent required legacy retention affect CPU reliability; capture-wide counters cannot establish an unaffected action window"
    rows["scheduler_cpu_warning"] = {"reason": reason, "errors": errors,
                                     "coverage": "Marker, native process counters, AM, and gfxinfo availability are evaluated separately"}
    if reported_loss:
        rows["scheduler_cpu_warning"]["reported_ftrace_buffer_loss"] = retention
    if missing_or_inconsistent_retention:
        rows["scheduler_cpu_warning"]["missing_or_inconsistent_legacy_retention"] = retention
    for metric in metrics:
        rows[metric] = unavailable(reason) | {"untrusted_estimates": rows[metric]}


def trace_summary(path, binary):
    evidence = {"path": str(path.resolve()), "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
                "size_bytes": path.stat().st_size, "trace_processor_exit_code": None}
    if evidence["size_bytes"] == 0:
        return evidence | unavailable("Trace file is empty; no measurement was captured")
    result = subprocess.run(["rtk", "proxy", str(binary), "query", str(path), trace_sql()],
                            capture_output=True, text=True, timeout=180)
    evidence.update({"trace_processor_exit_code": result.returncode, "trace_processor_stderr": result.stderr})
    if result.returncode:
        return evidence | unavailable("Trace Processor rejected this trace/query; inspect stderr")
    rows = parse_trace_output(result.stdout)
    duration = next((row["duration_ms"] for row in rows["bounds"]), None)
    for row in rows["cpu"]:
        row["one_core_percent_of_capture"] = (100 * row["cpu_ms"] / duration) if duration and duration > 0 else None
    frame_rows = rows.pop("frames")
    rows["frame_summary"] = summarize_frames(frame_rows)
    rows["frame_surface_sample_count"] = len(frame_rows)
    for metric in ("processes", "cpu", "memory_counters", "gc_named_slices", "database_named_slices",
                   "shell_process_candidates"):
        if not rows[metric]:
            rows[metric] = unavailable("No matching recorded evidence; absence does not prove zero work")
    rows["allocations"] = unavailable("No allocation profiler in this low-overhead system capture")
    rows["exact_database_query_count"] = unavailable("Named slices are candidates, not a complete query inventory")
    rows["exact_root_command_count"] = unavailable("su daemons can break ancestry; shell lifetimes are not command durations")
    rows["slice_caveat"] = "Named GC/database slices may nest; summed duration can double-count nested work"
    gate_scheduler_cpu(rows, ("cpu",))
    status = ({"status": "available"} if duration is not None and duration > 0
              else unavailable("No positive trace duration; capture is empty or incomplete"))
    return evidence | rows | status


def gfxinfo_action_window(iteration):
    capture = (iteration or {}).get("gfxinfoWindow")
    if not isinstance(capture, dict) or capture.get("clock") != "SystemClock.uptimeMillis":
        return unavailable("No recorded Android uptime gfxinfo command brackets")
    names = ("resetBeforeUptimeMillis", "resetAfterUptimeMillis", "actionStartUptimeMillis",
             "actionEndUptimeMillis", "dumpBeforeUptimeMillis", "dumpAfterUptimeMillis")
    values = [iteration.get(name) if name.startswith("action") else capture.get(name) for name in names]
    if (any(type(value) is not int or value < 0 for value in values) or values != sorted(values)
            or values[3] <= values[2]):
        return unavailable("Missing or unordered gfxinfo/action Android uptime brackets")
    return {"status": "available", "clock": capture["clock"], "capture_brackets": capture,
            "action_start_uptime_ms": values[2], "action_end_uptime_ms": values[3],
            "definition": "Frame timestamps compared with recorded Android action uptime milliseconds; "
                          "boundary milliseconds are conservative straddlers; no kernel trace clock mapping or sample clipping"}


def gfxinfo_summary(path, iteration=None):
    raw = path.read_text()
    action_window = gfxinfo_action_window(iteration)
    frames = []
    header = None
    active = False
    window = None
    for line_number, line in enumerate(raw.splitlines(), 1):
        if line.lstrip().startswith("Window:"):
            window = line.strip()
        if line.strip() == "---PROFILEDATA---":
            active = not active
            header = None
            continue
        if not active or not line.strip():
            continue
        cells = next(csv.reader([line]))
        while cells and not cells[-1].strip():
            cells.pop()
        if cells and cells[0].strip().lower() == "flags":
            header = [cell.strip() for cell in cells]
            continue
        if header is None:
            continue
        frame = {"line": line_number, "window": window, "raw": line, "exclusions": []}
        if len(cells) != len(header):
            frame["exclusions"].append("Column count differs from the PROFILEDATA header")
        else:
            try:
                frame["fields"] = dict(zip(header, map(int, cells)))
            except ValueError:
                frame["exclusions"].append("Non-integer frame field")
        fields = {re.sub(r"[^a-z]", "", key.lower()): value for key, value in frame.get("fields", {}).items()}
        if fields:
            if fields.get("flags") != 0:
                frame["exclusions"].append("Nonzero Flags: exceptional frame excluded from duration samples")
            start, end = fields.get("intendedvsync"), fields.get("framecompleted")
            if start is None or end is None or not 0 < start < end < 2**63 - 1:
                frame["exclusions"].append("Missing, incomplete, sentinel, or unordered frame timestamps")
            elif not frame["exclusions"]:
                frame["frame_completed_minus_intended_vsync_ms"] = (end - start) / 1e6
            if action_window["status"] == "available" and start is not None and end is not None and 0 < start < end < 2**63 - 1:
                action_start = action_window["action_start_uptime_ms"] * 1_000_000
                action_end = action_window["action_end_uptime_ms"] * 1_000_000
                if end <= action_start:
                    classification, reason = "before", "Frame completes before the recorded action start millisecond"
                elif start >= action_end + 1_000_000:
                    classification, reason = "after", "Frame starts after the recorded action end millisecond"
                elif start >= action_start + 1_000_000 and end <= action_end:
                    classification, reason = "inside", "Whole frame lies within the conservative Android action bounds"
                else:
                    classification, reason = "straddles", "Frame overlaps an action edge or its one-millisecond timestamp uncertainty"
                frame["action_window"] = {"status": "available", "classification": classification, "reason": reason}
                if start < action_start:
                    classification, reason = "before", "IntendedVsync starts before the recorded action start millisecond"
                elif start >= action_end + 1_000_000:
                    classification, reason = "after", "IntendedVsync starts after the recorded action end millisecond"
                elif action_start + 1_000_000 <= start < action_end:
                    classification, reason = "inside", "IntendedVsync starts inside conservative Android action bounds; completion may follow action end"
                else:
                    classification, reason = "boundary", "IntendedVsync starts within an action boundary millisecond of timestamp uncertainty"
                frame["action_start_window"] = {"status": "available", "classification": classification, "reason": reason}
            else:
                frame["action_window"] = unavailable(action_window.get("reason", "Frame timestamps are invalid for action-window classification"))
        frame.setdefault("action_window", unavailable("Frame fields are invalid for action-window classification"))
        frame.setdefault("action_start_window", unavailable(frame["action_window"].get("reason", "Frame fields are invalid for action-start classification")))
        frames.append(frame)
    total = re.search(r"(?m)^\s*Total frames rendered:\s*(\d+)", raw)
    jank = re.search(r"(?m)^\s*Janky frames:\s*(\d+)\s*\(([\d.]+)%\)", raw)
    samples = [frame["frame_completed_minus_intended_vsync_ms"] for frame in frames if not frame["exclusions"]]
    cohort_counts = dict.fromkeys(("before", "inside", "after", "straddles", "unavailable"), 0)
    start_cohort_counts = dict.fromkeys(("before", "inside", "after", "boundary", "unavailable"), 0)
    contained_samples = []
    started_samples = []
    for frame in frames:
        if not frame["exclusions"]:
            cohort = frame["action_window"].get("classification", "unavailable")
            cohort_counts[cohort] += 1
            if cohort == "inside":
                contained_samples.append(frame["frame_completed_minus_intended_vsync_ms"])
            start_cohort = frame["action_start_window"].get("classification", "unavailable")
            start_cohort_counts[start_cohort] += 1
            if start_cohort == "inside":
                started_samples.append(frame["frame_completed_minus_intended_vsync_ms"])
    contained = (distribution(contained_samples) if contained_samples else unavailable(
        action_window.get("reason", "No eligible retained frames are wholly contained in the Android action window")))
    contained.update({"eligible_cohort_counts": cohort_counts,
                      "coverage": "Diagnostic strictly action-contained subset of eligible retained frames, not complete interaction latency; "
                                  "boundary/straddling and outside frames remain in the reset-to-dump population and raw rows"})
    started = (distribution(started_samples) if started_samples else unavailable(
        action_window.get("reason", "No eligible retained frames start inside conservative Android action bounds")))
    started.update({"eligible_start_cohort_counts": start_cohort_counts,
                    "coverage": "Primary action-start cohort of eligible retained frames; full FrameCompleted minus IntendedVsync "
                                "includes completion after action end. Start boundary milliseconds are separate; all raw rows remain. "
                                "Retained subset, not complete interaction latency; no kernel trace clock mapping"})
    return {"path": str(path.resolve()), "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
            "status": "available" if samples else "unavailable", "retained_frames": frames,
            "retained_frame_count": len(frames), "excluded_frame_count": len(frames) - len(samples),
            "retained_frame_completed_minus_intended_vsync_ms": distribution(samples),
            "action_contained_frame_completed_minus_intended_vsync_ms": contained,
            "action_started_frame_completed_minus_intended_vsync_ms": started,
            "android_reported_total_frames": int(total[1]) if total else unavailable("No Android total counter"),
            "android_reported_jank": {"frames": int(jank[1]), "percent": float(jank[2])} if jank else unavailable("No Android jank counter"),
            "action_window": action_window,
            "definition": "Raw gfxinfo timestamp difference, not FrameTimeline or Macrobenchmark frameDurationCpuMs",
            "coverage": "Recent retained frame ring after reset; Android aggregate counters cover the broader reset-to-dump window and can include more frames; not action-clipped"}


def am_start_summary(path, requested_mode):
    raw = path.read_text()
    fields = dict(re.findall(r"(?m)^\s*(Status|LaunchState|ThisTime|TotalTime|WaitTime):\s*([^\r\n]+)", raw))
    state = fields.get("LaunchState", "").strip().upper()
    confirmed = state == requested_mode and fields.get("Status", "").strip().lower() == "ok"
    timings = {}
    for field in ("ThisTime", "TotalTime", "WaitTime"):
        value = fields.get(field, "").strip()
        timings[f"am_start_{field}_ms"] = int(value) if re.fullmatch(r"\d+", value) else unavailable(f"No numeric {field}")
    return {"path": str(path.resolve()), "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
            "status": "available" if confirmed and any(type(value) is int for value in timings.values()) else "unavailable", "raw": raw,
            "requested_launch_state": requested_mode, "reported_launch_state": state or unavailable("No LaunchState"),
            "launch_state_matches": confirmed, "timings": timings,
            "definition": "Native am start -W reported timings; not Macrobenchmark TTID or timeToInitialDisplayMs"}


def proc_stat_fields(raw, expected_pid):
    left, right = raw.find("("), raw.rfind(")")
    if left < 0 or right <= left or raw[:left].strip() != str(expected_pid):
        raise ValueError("Malformed /proc/PID/stat or PID mismatch")
    fields = raw[right + 1:].split()
    if len(fields) < 20 or len(fields[0]) != 1:
        raise ValueError("Incomplete /proc/PID/stat fields")
    selected = {name: fields[index] for name, index in (("utime_ticks", 11), ("stime_ticks", 12), ("starttime_ticks", 19))}
    if any(not re.fullmatch(r"\d+", value) for value in selected.values()):
        raise ValueError("Invalid /proc/PID/stat CPU or identity counter")
    memory = {}
    for name, index in (("vsize_bytes", 20), ("rss_pages", 21)):
        memory[name] = (int(fields[index]) if len(fields) > index and re.fullmatch(r"\d+", fields[index])
                        else unavailable(f"Missing or unsupported stat field {index + 3} ({name})"))
    return {"pid": expected_pid, "comm": raw[left + 1:right], **{key: int(value) for key, value in selected.items()}, **memory}


def stat_read_bracket(item):
    result = {key: item.get(key) for key in ("stat_read_start_uptime_raw", "stat_read_end_uptime_raw")}
    values = []
    for key in result:
        match = re.fullmatch(r"(\d+(?:\.\d+)?)\s+\d+(?:\.\d+)?", result[key] or "")
        if not match:
            return result | unavailable("Missing or invalid per-PID stat read uptime bracket")
        value = match[1]
        precision = len(value.partition(".")[2])
        values.append((Decimal(value) * 1000, Decimal(10) ** -precision * 1000))
    (start, start_unit), (end, end_unit) = values
    if end < start:
        return result | unavailable("Per-PID stat read uptime bracket is reversed")
    return result | {"status": "available", "clock": "/proc/uptime",
                     "lower_uptime_ms": float(max(0, start - start_unit)),
                     "upper_uptime_ms": float(end + end_unit),
                     "definition": "Stat read bracket expanded by one printed uptime unit at each edge; includes display precision"}


def proc_observation_interval(first, last, cpu_ms):
    before = first.get("stat_read_bracket", {}) if first else {}
    after = last.get("stat_read_bracket", {})
    reason = "Paired per-PID stat read uptime brackets are unavailable; CPU totals remain usable"
    if before.get("status") != "available" or after.get("status") != "available":
        return {"observation_interval": unavailable(reason), "one_core_percent": unavailable(reason)}
    lower = after["lower_uptime_ms"] - before["upper_uptime_ms"]
    upper = after["upper_uptime_ms"] - before["lower_uptime_ms"]
    if lower <= 0 or upper < lower:
        reason = "Stat observation brackets overlap or are unordered; no positive normalization interval"
        return {"observation_interval": unavailable(reason), "one_core_percent": unavailable(reason)}
    midpoint = (lower + upper) / 2
    return {"observation_interval": {"status": "available", "clock": "/proc/uptime",
             "lower_ms": lower, "upper_ms": upper, "midpoint_estimate_ms": midpoint,
             "definition": "Elapsed time between this PID's two stat observations, bounded by their read brackets; no kernel marker mapping"},
            "one_core_percent": {"status": "available", "midpoint_estimate": 100 * cpu_ms / midpoint,
             "lower_interval_estimate": 100 * cpu_ms / upper, "upper_interval_estimate": 100 * cpu_ms / lower,
             "definition": "CPU milliseconds / observation milliseconds * 100; one core is 100%; denominator-only range, CPU deltas retain CLK_TCK quantization"}}


def proc_cpu_snapshot(path):
    raw = path.read_text()
    evidence = {"path": str(path.resolve()), "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}
    processes = {}
    process = None
    clock = None
    uptime = None
    try:
        for line in raw.splitlines():
            if line.startswith("clockTicksPerSecond:"):
                value = line.partition(":")[2].strip()
                if clock is not None or not re.fullmatch(r"[1-9]\d*", value):
                    raise ValueError("Missing, invalid, or duplicate actual CLK_TCK value")
                clock = int(value)
            elif line.startswith("snapshotUptime:"):
                uptime = line.partition(":")[2].strip()
            elif line.startswith("process:"):
                process = line.partition(":")[2]
                if process not in PROCESSES or process in processes:
                    raise ValueError("Unknown or duplicate process in CPU snapshot")
                processes[process] = []
            elif line.startswith("pid:"):
                value = line.partition(":")[2].strip()
                if process is None or not re.fullmatch(r"[1-9]\d*", value):
                    raise ValueError("Missing process label or invalid PID")
                processes[process].append({"pid": int(value), **unavailable("No usable stat line")})
            elif line.startswith("stat:"):
                if process is None or not processes[process] or "raw_stat" in processes[process][-1]:
                    raise ValueError("Stat line lacks an unambiguous PID")
                item = processes[process][-1]
                item["raw_stat"] = line.partition(":")[2]
                try:
                    item.update(proc_stat_fields(item["raw_stat"], item["pid"]))
                    item.pop("reason", None)
                    item["status"] = "available"
                except ValueError as error:
                    item.update(unavailable(str(error)))
            elif line.startswith(("statReadStartUptime:", "statReadEndUptime:")):
                if process is None or not processes[process]:
                    raise ValueError("Stat read uptime bracket lacks an unambiguous PID")
                item = processes[process][-1]
                start = line.startswith("statReadStartUptime:")
                key = "stat_read_start_uptime_raw" if start else "stat_read_end_uptime_raw"
                if key in item or (start == ("raw_stat" in item)):
                    raise ValueError("Duplicate or unordered per-PID stat read uptime bracket")
                item[key] = line.partition(":")[2].strip()
            elif line.strip():
                raise ValueError("Unrecognized CPU snapshot output")
        if clock is None:
            raise ValueError("Actual CLK_TCK was not recorded")
    except ValueError as error:
        return evidence | unavailable(str(error))
    for items in processes.values():
        for item in items:
            item["stat_read_bracket"] = stat_read_bracket(item)
    return evidence | {"status": "available", "clock_ticks_per_second": clock,
                       "snapshot_uptime_raw": uptime, "processes": processes}


def proc_cpu_summary(before_path, after_path, confirmed_cold=False):
    before, after = proc_cpu_snapshot(before_path), proc_cpu_snapshot(after_path)
    result = {"before": before, "after": after,
              "definition": "Delta of /proc/PID/stat fields 14 utime and 15 stime divided by actual getconf CLK_TCK; field 22 starttime guards PID reuse",
              "memory_definition": "Before/after /proc/PID/stat field 23 vsize in bytes and field 24 rss in raw pages, using the same PID/starttime guard; page size unknown; not peak, heap, or allocation measurements",
              "window": "Process snapshots immediately before native S and after native F; includes snapshot/marker command overhead, not pure UI action time"}
    if before["status"] != "available" or after["status"] != "available":
        return result | unavailable("At least one process CPU snapshot is unavailable")
    clock = before["clock_ticks_per_second"]
    if clock != after["clock_ticks_per_second"]:
        return result | unavailable("CLK_TCK differs between snapshots")
    metrics = {}
    for process in PROCESSES:
        first, last = before["processes"].get(process), after["processes"].get(process)
        reason = None
        if first is None or last is None or len(last) != 1 or len(first) > 1:
            reason = "Process missing, exited, or has ambiguous snapshot identity"
        elif last[0]["status"] != "available" or any(item["status"] != "available" for item in first):
            reason = "Required /proc/PID/stat counters are unavailable"
        elif first and any(first[0][key] != last[0][key] for key in ("pid", "starttime_ticks")):
            reason = "PID or starttime changed; process CPU deltas cannot cross process lifetimes"
        elif not first and not confirmed_cold:
            reason = "New process has no before counters; since-birth CPU requires confirmed COLD startup and an explicitly empty before process"
        if reason:
            metrics[process] = unavailable(reason)
            continue
        deltas = {key: last[0][key] - (first[0][key] if first else 0) for key in ("utime_ticks", "stime_ticks")}
        if any(value < 0 for value in deltas.values()):
            metrics[process] = unavailable("CPU counters decreased")
            continue
        metrics[process] = {"status": "available", "pid": last[0]["pid"], "starttime_ticks": last[0]["starttime_ticks"],
                            "basis": "same_process_delta" if first else "since_process_birth_during_confirmed_cold_start",
                            "utime_delta_ticks": deltas["utime_ticks"], "stime_delta_ticks": deltas["stime_ticks"],
                            "user_cpu_ms": deltas["utime_ticks"] * 1000 / clock,
                            "system_cpu_ms": deltas["stime_ticks"] * 1000 / clock,
                            "cpu_ms": sum(deltas.values()) * 1000 / clock, "clock_ticks_per_second": clock,
                            "counter_tick_ms": 1000 / clock,
                            "memory_before": ({key: first[0][key] for key in ("vsize_bytes", "rss_pages")} if first
                                              else unavailable("Process absent before confirmed COLD launch")),
                            "memory_after": {key: last[0][key] for key in ("vsize_bytes", "rss_pages")}}
        metrics[process].update(proc_observation_interval(first[0] if first else None, last[0], metrics[process]["cpu_ms"]))
    return result | {"status": "available" if any(item["status"] == "available" for item in metrics.values()) else "unavailable",
                     "processes": metrics}


def legacy_artifact(directory, name):
    if not isinstance(name, str) or not name or Path(name).name != name or "\\" in name:
        raise ValueError("Legacy artifact must be a relative basename")
    path = directory / name
    if path.resolve().parent != directory.resolve():
        raise ValueError("Legacy artifact resolves outside its result directory")
    return path


def legacy_trace_sql(iteration):
    marker_pid = iteration["traceMarkerPid"]
    section = iteration["traceSection"]
    if type(marker_pid) is not int or marker_pid <= 0 or not isinstance(section, str) or not section or len(section) > 127:
        raise ValueError("Invalid legacy trace marker identity")
    marker_type = iteration.get("markerType", "androidTrace")
    if marker_type not in ("androidTrace", "nativeAsync"):
        raise ValueError("Unknown legacy marker type")
    cookie = iteration.get("markerCookie")
    if marker_type == "nativeAsync" and (type(cookie) is not int or not 0 < cookie <= 2**31 - 1 or "|" in section):
        raise ValueError("Native async marker requires a positive signed-32-bit cookie and pipe-free section")
    values = []
    tids = []
    tid_owners = {}
    observed = set()
    for process, pids in iteration["processPids"].items():
        if process not in PROCESSES or not isinstance(pids, list):
            raise ValueError("Legacy PID map must identify only the profiling main/service processes")
        for pid in set(pids):
            if type(pid) is not int or pid <= 0 or pid in observed:
                raise ValueError("Legacy process PIDs must be positive and unambiguous")
            observed.add(pid)
            values.append(f"('{process}', {pid})")
            for tid in set(iteration.get("processTids", {}).get(str(pid), [])):
                if type(tid) is not int or tid <= 0:
                    raise ValueError("Observed legacy task TIDs must be positive integers")
                if tid in tid_owners and tid_owners[tid] != pid:
                    raise ValueError("Observed task TID has ambiguous process ownership")
                tid_owners[tid] = pid
                tids.append(f"('{process}', {pid}, {tid})")
    targets = "VALUES " + ",".join(values) if values else "SELECT '', -1 WHERE 0"
    thread_targets = "VALUES " + ",".join(tids) if tids else "SELECT '', -1, -1 WHERE 0"
    section_sql = section.replace("'", "''")
    marker_source = ("JOIN process_track pt ON pt.id=s.track_id JOIN process p ON p.upid=pt.upid"
                     if marker_type == "nativeAsync" else
                     "JOIN thread_track tt ON tt.id=s.track_id JOIN thread t ON t.utid=tt.utid JOIN process p ON p.upid=t.upid")
    cookie_filter = f" AND EXTRACT_ARG(s.arg_set_id,'cookie')={cookie}" if marker_type == "nativeAsync" else ""
    return f"""
WITH targets(label, pid) AS ({targets}), observed_tids(label,pid,tid) AS ({thread_targets}), markers AS (
 SELECT s.ts, s.dur FROM slice s {marker_source}
 WHERE s.name='{section_sql}' AND p.pid={marker_pid} AND s.dur>0{cookie_filter}
), window AS (SELECT * FROM markers WHERE (SELECT COUNT(*) FROM markers)=1),
target_slices AS (
 SELECT targets.label, s.name, MIN(s.ts+s.dur,w.ts+w.dur)-MAX(s.ts,w.ts) AS dur
 FROM slice s JOIN thread_track tt ON tt.id=s.track_id JOIN thread t ON t.utid=tt.utid
 JOIN process p ON p.upid=t.upid JOIN targets ON targets.pid=p.pid CROSS JOIN window w
 WHERE s.dur>=0 AND s.ts<w.ts+w.dur AND s.ts+s.dur>w.ts
)
SELECT 'window' metric, hex(json_group_array(json_object('ts_ns',ts,'duration_ns',dur))) data FROM markers
UNION ALL
SELECT 'main_thread_cpu', hex(json_group_array(json_object('process',label,'pid',pid,'cpu_ms',cpu_ms,'slices',slices))) FROM (
 SELECT targets.label,targets.pid,SUM(MIN(s.ts+s.dur,w.ts+w.dur)-MAX(s.ts,w.ts))/1e6 cpu_ms,COUNT(*) slices
 FROM sched s JOIN thread t ON t.utid=s.utid JOIN targets ON targets.pid=t.tid CROSS JOIN window w
 WHERE s.dur>0 AND s.ts<w.ts+w.dur AND s.ts+s.dur>w.ts GROUP BY targets.label,targets.pid
)
UNION ALL
SELECT 'mapped_process_cpu', hex(json_group_array(json_object('process',label,'pid',pid,'cpu_ms',cpu_ms,'slices',slices))) FROM (
 SELECT targets.label,targets.pid,SUM(MIN(s.ts+s.dur,w.ts+w.dur)-MAX(s.ts,w.ts))/1e6 cpu_ms,COUNT(*) slices
 FROM sched s JOIN thread t ON t.utid=s.utid JOIN process p ON p.upid=t.upid
 JOIN targets ON targets.pid=p.pid CROSS JOIN window w
 WHERE s.dur>0 AND s.ts<w.ts+w.dur AND s.ts+s.dur>w.ts GROUP BY targets.label,targets.pid
)
UNION ALL
SELECT 'observed_task_cpu', hex(json_group_array(json_object('process',label,'pid',pid,'tid',tid,'cpu_ms',cpu_ms,'slices',slices))) FROM (
 SELECT targets.label,targets.pid,targets.tid,SUM(MIN(s.ts+s.dur,w.ts+w.dur)-MAX(s.ts,w.ts))/1e6 cpu_ms,COUNT(*) slices
 FROM sched s JOIN thread t ON t.utid=s.utid JOIN observed_tids targets ON targets.tid=t.tid CROSS JOIN window w
 WHERE s.dur>0 AND s.ts<w.ts+w.dur AND s.ts+s.dur>w.ts GROUP BY targets.label,targets.pid,targets.tid
)
UNION ALL
SELECT 'gc_database_named_slices', hex(json_group_array(json_object('process',label,'name',name,'count',count,'total_ms',total_ms))) FROM (
 SELECT label,name,COUNT(*) count,SUM(dur)/1e6 total_ms FROM target_slices
 WHERE name GLOB '*GC*' OR LOWER(name) LIKE '%garbage collect%' OR LOWER(name) LIKE '%sqlite%'
 OR LOWER(name) LIKE 'query%' OR LOWER(name) LIKE 'execute%' OR LOWER(name) LIKE 'prepare%' OR LOWER(name) LIKE '%database%'
 GROUP BY label,name
)
UNION ALL
SELECT 'shell_process_candidates', hex(json_group_array(json_object('name',p.name,'pid',p.pid,
 'parent_upid',p.parent_upid,'start_ns',p.start_ts,'end_ns',p.end_ts)))
 FROM process p CROSS JOIN window w WHERE p.name IN ('su','sh','bash','toybox','busybox')
 AND (p.start_ts IS NULL OR p.start_ts<w.ts+w.dur) AND (p.end_ts IS NULL OR p.end_ts>w.ts)
UNION ALL
SELECT 'trace_health', hex(json_group_array(json_object('name',name,'value',value,'severity',severity)))
 FROM stats WHERE value!=0 AND severity!='info'
""".strip()


def legacy_trace_summary(path, binary, iteration):
    if path.stat().st_size == 0:
        return unavailable("Legacy trace file is empty")
    sql = legacy_trace_sql(iteration)
    raw_lines = path.read_text(errors="replace").splitlines()
    result = subprocess.run(["rtk", "proxy", str(binary), "query", str(path), sql],
                            capture_output=True, text=True, timeout=180)
    evidence = {"path": str(path.resolve()), "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
                "sql": sql, "trace_processor_exit_code": result.returncode, "stderr": result.stderr,
                "legacy_ftrace_retention": legacy_ftrace_retention(raw_lines)}
    if result.returncode:
        return evidence | unavailable("Trace Processor rejected the legacy trace/query")
    rows = parse_trace_output(result.stdout)
    rows["legacy_ftrace_retention"] = evidence["legacy_ftrace_retention"]
    if len(rows["window"]) != 1:
        return evidence | rows | unavailable("Expected exactly one positive-duration named ATrace marker for the benchmark PID")
    if iteration.get("markerType") == "nativeAsync":
        markers = {"S": 0, "F": 0}
        for line in raw_lines:
            match = re.search(r":\s*([SF])\|(\d+)\|([^|]+)\|(-?\d+)\s*$", line)
            if match and (int(match[2]), match[3], int(match[4])) == (
                    iteration["traceMarkerPid"], iteration["traceSection"], iteration["markerCookie"]):
                markers[match[1]] += 1
        rows["native_async_raw_markers"] = markers
        if markers != {"S": 1, "F": 1}:
            return evidence | rows | unavailable("Native async window requires exactly one raw matching S and F marker")
    sched_prefixes = []
    for line in raw_lines:
        match = re.search(r"-(\d+)\s+(?:\(\s*(\d+|-+)\s*\)\s+)?\[\d+\].*sched_switch:", line)
        if match and int(match[1]) > 0:
            sched_prefixes.append(match[2] is not None and match[2].isdigit() and int(match[2]) > 0)
    complete_tgid = bool(sched_prefixes) and all(sched_prefixes)
    rows["tgid_mapping"] = {"scheduler_lines_checked": len(sched_prefixes), "with_tgid": sum(sched_prefixes),
                            "complete": complete_tgid}
    for key in ("main_thread_cpu", "observed_task_cpu", "gc_database_named_slices", "shell_process_candidates"):
        if not rows[key]:
            rows[key] = unavailable("No matching recorded evidence inside the exact trace marker window")
    if not complete_tgid:
        rows["mapped_process_cpu"] = unavailable("Legacy trace lacks complete TGID mapping; whole-process CPU cannot be established") | {
            "known_mapped_observations": rows["mapped_process_cpu"]}
    elif not rows["mapped_process_cpu"]:
        rows["mapped_process_cpu"] = unavailable("No mapped process scheduler slices in this window")
    rows["definition"] = "Completed scheduler slices clipped to the exact named marker; main thread matched by observed PID"
    rows["marker_type"] = iteration.get("markerType", "androidTrace")
    rows["marker_identity"] = {"pid": iteration["traceMarkerPid"], "section": iteration["traceSection"]}
    if rows["marker_type"] == "nativeAsync":
        rows["marker_identity"]["cookie"] = iteration["markerCookie"]
    rows["window_provenance"] = ("Root shell native async S/F bracket includes marker transport overhead; not pure UI action time"
                                  if rows["marker_type"] == "nativeAsync" else "Thread android.os.Trace begin/end section")
    rows["observed_task_cpu_definition"] = "Only task TIDs present in before/post-launch/after snapshots; short-lived or expired workers can be missing, so this is partial process coverage"
    rows["candidate_caveat"] = "Shells with unknown lifetimes can fall outside the marker; named GC/database slices can nest and summed durations can double-count"
    rows["exact_query_and_root_counts"] = unavailable("Named slices/shell candidates are incomplete; missing cmdlines/ancestry and nested slices prevent exact counts")
    rows["frame_timeline"] = unavailable("Legacy ATrace has no FrameTimeline; gfxinfo is reported separately")
    gate_scheduler_cpu(rows, ("main_thread_cpu", "mapped_process_cpu", "observed_task_cpu"), require_legacy_retention=True)
    rows["binder_transactions"] = binder_transactions_summary(raw_lines, iteration, rows)
    return evidence | rows | {"status": "available"}


def legacy_results_summary(path, binary):
    data = json.loads(path.read_text())
    if data.get("schemaVersion") != 1 or data.get("measurementBackend") != "legacyAtrace" or data.get("packageName") != PROCESSES[0]:
        raise ValueError("Unsupported legacy result schema/backend/package")
    iterations = []
    for item in data.get("iterations", []):
        result = {"metadata": item}
        for field, output, parser in (("frameStatsFile", "gfxinfo", lambda file: gfxinfo_summary(file, item)),
                                      ("traceFile", "atrace", lambda file: legacy_trace_summary(file, binary, item))):
            if not item.get(field):
                result[output] = unavailable(f"No {field} artifact recorded")
                continue
            artifact = legacy_artifact(path.parent, item[field])
            result[output] = parser(artifact) if artifact.is_file() else unavailable(f"Artifact missing: {artifact.name}")
        startup = item.get("startup")
        if startup:
            artifact = legacy_artifact(path.parent, startup["amStartFile"])
            result["am_start"] = am_start_summary(artifact, startup["requestedMode"]) if artifact.is_file() else unavailable("AM startup artifact missing")
        if item.get("cpuCountersBeforeFile") and item.get("cpuCountersAfterFile"):
            before = legacy_artifact(path.parent, item["cpuCountersBeforeFile"])
            after = legacy_artifact(path.parent, item["cpuCountersAfterFile"])
            cold = bool(startup and startup["requestedMode"] == "COLD" and result["am_start"]["status"] == "available")
            result["proc_cpu"] = proc_cpu_summary(before, after, cold) if before.is_file() and after.is_file() else unavailable("Process CPU counter artifact missing")
        else:
            result["proc_cpu"] = unavailable("No paired native process CPU counter artifacts recorded")
        result["status"] = ("available" if item.get("status") == "passed" and item.get("traceCaptureStatus") == "captured"
                            and result["atrace"]["status"] == "available"
                            and (not startup or result["am_start"]["status"] == "available") else "unavailable")
        iterations.append(result)
    return {"path": str(path.resolve()), "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
            "scenario": data.get("scenario"), "measurementBackend": "legacyAtrace",
            "status": "available" if iterations and all(item["status"] == "available" for item in iterations) else "unavailable",
            "iteration_count": len(iterations), "iterations": iterations,
            "composition_tracing": unavailable("Individual Compose tracing requires Perfetto; no supported ATrace forwarding API"),
            "definition": "Native ATrace, gfxinfo, and AM fallback; not genuine Macrobenchmark results"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--benchmark", action="append", type=Path, default=[])
    parser.add_argument("--trace", action="append", type=Path, default=[])
    parser.add_argument("--legacy-results", action="append", type=Path, default=[])
    parser.add_argument("--trace-processor", type=Path, help="Existing native binary; never downloaded")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if not args.benchmark and not args.trace and not args.legacy_results:
        parser.error("Supply --benchmark, --trace, and/or --legacy-results")
    if (args.trace or args.legacy_results) and (args.trace_processor is None or not args.trace_processor.is_file()):
        parser.error("Trace/legacy analysis requires --trace-processor pointing to an existing binary")
    report = {"percentiles": "Linear interpolation over pooled samples; compare equal scenarios and iteration counts",
              "benchmarks": [benchmark_summary(path) for path in args.benchmark],
              "traces": [trace_summary(path, args.trace_processor) for path in args.trace],
              "legacy_results": [legacy_results_summary(path, args.trace_processor) for path in args.legacy_results]}
    if args.trace or args.legacy_results:
        version = subprocess.run(["rtk", "proxy", str(args.trace_processor), "--version"],
                                 capture_output=True, text=True, timeout=30)
        report["trace_processor_version"] = version.stdout.strip() or version.stderr.strip()
        report["sql"] = trace_sql()
    with args.output.open("x") as output:
        output.write(json.dumps(report, indent=2, ensure_ascii=False, allow_nan=False) + "\n")
    if any(result["status"] != "available" for result in report["traces"] + report["legacy_results"]):
        raise SystemExit(1)


if __name__ == "__main__":
    main()
