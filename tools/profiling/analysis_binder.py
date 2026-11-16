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

"""Count observable outgoing Binder events in one raw native-marker window."""

from decimal import Decimal
import re

from capture import PROCESSES


def binder_transactions_summary(raw_lines, iteration, trace_rows):
    reasons = []
    categories = iteration.get("captureCategories")
    if not isinstance(categories, list) or "binder_driver" not in categories:
        reasons.append("captureCategories does not establish binder_driver capture")
    retention = trace_rows.get("legacy_ftrace_retention", {})
    if (retention.get("status") != "available" or retention.get("header_body_consistent") is not True
            or retention.get("reported_overwritten_entries") != 0):
        reasons.append("Capture-wide ftrace retention is missing, inconsistent, or reports loss")
    scheduler_errors = {"mismatched_sched_switch_tids", "sched_switch_out_of_order", "invalid_cpu_times",
                        "task_state_invalid", "systrace_parse_failure"}
    health = trace_rows.get("trace_health")
    errors = [item for item in health if str(item.get("severity", "")).replace(" ", "_") in ("error", "data_loss")
              and (item.get("name") in scheduler_errors or str(item.get("name", "")).startswith("ftrace_"))] if isinstance(health, list) else []
    if not isinstance(health, list) or errors:
        reasons.append("Scheduler/loss health is missing or reports integrity errors")

    owners = {}
    processes = {}
    pid_map, tid_map = iteration.get("processPids"), iteration.get("processTids")
    if not isinstance(pid_map, dict) or not isinstance(tid_map, dict):
        reasons.append("Observed process PID/task TID maps are missing")
        pid_map, tid_map = {}, {}
    for process, pids in pid_map.items():
        if process not in PROCESSES or not isinstance(pids, list):
            reasons.append("Observed process PID map has an invalid target or PID list")
            continue
        processes[process] = {"pids": [], "observed_sender_tids": []}
        for pid in pids:
            if type(pid) is not int or pid <= 0:
                reasons.append("Observed process PID is not a positive integer")
                continue
            processes[process]["pids"].append(pid)
            tids = tid_map.get(str(pid), [])
            if not isinstance(tids, list):
                reasons.append("Observed task TIDs are not a list")
                continue
            for tid in [pid, *tids]:
                if type(tid) is not int or tid <= 0:
                    reasons.append("Observed task TID is not a positive integer")
                    continue
                owners.setdefault(tid, set()).add((process, pid))
    if any(len(values) != 1 for values in owners.values()):
        reasons.append("Observed sender TID ownership is ambiguous")
    for tid, values in owners.items():
        if len(values) == 1:
            process, _ = next(iter(values))
            processes[process]["observed_sender_tids"].append(tid)

    identity = (iteration.get("traceMarkerPid"), iteration.get("traceSection"), iteration.get("markerCookie"))
    valid_identity = (iteration.get("markerType") == "nativeAsync" and type(identity[0]) is int and identity[0] > 0
                      and isinstance(identity[1], str) and 0 < len(identity[1]) <= 127 and "|" not in identity[1]
                      and type(identity[2]) is int and 0 < identity[2] <= 2**31 - 1)
    if not valid_identity:
        reasons.append("Exact native async marker identity is missing or invalid")
    event_pattern = re.compile(r"^\s*.+-(\d+)\s+(?:\([^)]*\)\s+)?\[\d+\].*?\s(\d+\.\d+):\s+(\w+):\s*(.*)$")
    marker_pattern = re.compile(r":\s*([SF])\|(\d+)\|([^|]+)\|(-?\d+)\s*$")
    markers = {"S": [], "F": []}
    events = []
    for number, line in enumerate(raw_lines, 1):
        match = event_pattern.match(line)
        marker = marker_pattern.search(line)
        if valid_identity and marker and (int(marker[2]), marker[3], int(marker[4])) == identity:
            markers[marker[1]].append({"line": number, "raw": line,
                                       "timestamp_seconds": match[2] if match and match[3] == "tracing_mark_write" else None})
        if "binder_transaction:" not in line:
            continue
        event = {"line": number, "raw": line, "reasons": []}
        if not match or match[3] != "binder_transaction":
            event["reasons"].append("Malformed binder_transaction event prefix/timestamp")
            reasons.append("A Binder event cannot be placed in the raw marker window")
        else:
            event.update({"sender_tid": int(match[1]), "timestamp_seconds": match[2]})
            destinations = re.findall(r"(?:^|\s)dest_proc=(\S+)", match[4])
            if len(destinations) != 1 or not re.fullmatch(r"[1-9]\d*", destinations[0]):
                event["reasons"].append("dest_proc is not one positive integer")
            else:
                event["destination_pid"] = int(destinations[0])
        events.append(event)

    recorded_events = sum("destination_pid" in event for event in events)
    coverage = {"status": "available" if recorded_events else "unavailable",
                "valid_positive_destination_events": recorded_events,
                "scope": "Capture-wide valid raw Binder events, including events outside the action"}
    if not recorded_events:
        coverage["reason"] = "No valid recorded Binder event establishes actual event capture"
        reasons.append(coverage["reason"])

    window = {"status": "unavailable", "reason": "Expected exactly one ordered raw native S/F pair"}
    if all(len(markers[kind]) == 1 and markers[kind][0]["timestamp_seconds"] is not None for kind in markers):
        start, finish = (Decimal(markers[kind][0]["timestamp_seconds"]) for kind in ("S", "F"))
        if start < finish and markers["S"][0]["line"] < markers["F"][0]["line"]:
            window = {"status": "available", "start_seconds": str(start), "end_seconds": str(finish),
                      "duration_seconds": float(finish - start), "interval": "[S,F)",
                      "clock": "Raw ftrace decimal timestamps; no Trace Processor epoch conversion"}
    if window["status"] != "available":
        reasons.append(window["reason"])

    counts = dict.fromkeys(processes, 0)
    malformed = dict.fromkeys(processes, False)
    for event in events:
        if "sender_tid" not in event:
            continue
        values = owners.get(event["sender_tid"], set())
        if len(values) == 1:
            event["process"], event["pid"] = next(iter(values))
        else:
            event["reasons"].append("Sender TID is unmapped or has ambiguous observed process ownership")
        if window["status"] != "available":
            event["window"] = "unavailable"
            continue
        event["window"] = "inside" if start <= Decimal(event["timestamp_seconds"]) < finish else "outside"
        if event["window"] == "inside" and "process" in event:
            if "destination_pid" in event:
                counts[event["process"]] += 1
            else:
                malformed[event["process"]] = True
    for process, evidence in processes.items():
        evidence["pids"] = sorted(set(evidence["pids"]))
        evidence["observed_sender_tids"].sort()
        process_reasons = list(dict.fromkeys(reasons))
        if not evidence["observed_sender_tids"]:
            process_reasons.append("No unambiguous observed sender TIDs for this process")
        if malformed[process]:
            process_reasons.append("An in-window event from this process has malformed destination evidence")
        metric = {"status": "unavailable", "reasons": process_reasons, "partial_evidence": {"count": counts[process]}}
        if not process_reasons:
            metric = {"status": "available", "count": counts[process],
                      "rate_per_second": counts[process] / window["duration_seconds"]}
        evidence["outgoing_observed_sender_transactions"] = metric
    return {"status": "unavailable" if reasons or not processes else "available", "reasons": list(dict.fromkeys(reasons)),
            "raw_marker_window": window, "raw_markers": markers, "processes": processes, "event_evidence": events,
            "recorded_binder_coverage": coverage,
            "capture_health": {"retention": retention, "scheduler_loss_errors": errors},
            "definition": "Outgoing binder_transaction event count/rate for unambiguously observed sender TIDs and positive dest_proc only",
            "whole_process_ipc": {"status": "unavailable", "reason": "Observed PID/task snapshots can miss workers; unmapped senders cannot be attributed and event counts do not establish complete process IPC or acquisition behavior"}}
