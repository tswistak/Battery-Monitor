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

import unittest

from analysis_binder import binder_transactions_summary
from capture import PROCESSES


class BinderAnalysisTests(unittest.TestCase):
    def test_raw_marker_window_observed_senders_and_capture_health(self):
        iteration = {"markerType": "nativeAsync", "traceMarkerPid": 50, "traceSection": "battery/test/1",
                     "markerCookie": 123, "captureCategories": ["sched", "binder_driver"],
                     "processPids": {PROCESSES[0]: [100], PROCESSES[1]: [200]},
                     "processTids": {"100": [100, 101], "200": [200]}}
        rows = {"legacy_ftrace_retention": {"status": "available", "header_body_consistent": True,
                                           "reported_overwritten_entries": 0}, "trace_health": [],
                "window": [{"ts_ns": 1, "duration_ns": 2}]}

        def line(tid, stamp, event):
            return f"worker-{tid} ( {tid} ) [000] .... {stamp}: {event}"

        start = line(300, "500001.000000001", "tracing_mark_write: S|50|battery/test/1|123")
        finish = line(301, "500003.000000001", "tracing_mark_write: F|50|battery/test/1|123")
        valid = line(101, "500002.000000001", "binder_transaction: transaction=1 dest_proc=999 reply=0")
        outside = line(101, "500003.000000001", "binder_transaction: transaction=2 dest_proc=999")
        raw = [start, valid, finish, outside]
        summary = binder_transactions_summary(raw, iteration, rows)
        metric = summary["processes"][PROCESSES[0]]["outgoing_observed_sender_transactions"]
        self.assertEqual(metric, {"status": "available", "count": 1, "rate_per_second": 0.5})
        self.assertEqual(summary["processes"][PROCESSES[1]]["outgoing_observed_sender_transactions"]["count"], 0)
        self.assertEqual(summary["raw_marker_window"]["start_seconds"], "500001.000000001")
        self.assertEqual(summary["event_evidence"][-1]["window"], "outside")
        self.assertEqual(summary["whole_process_ipc"]["status"], "unavailable")
        missing = binder_transactions_summary([start, finish], iteration, rows)
        self.assertEqual(missing["status"], "unavailable")
        self.assertEqual(missing["recorded_binder_coverage"]["valid_positive_destination_events"], 0)
        self.assertEqual(missing["processes"][PROCESSES[0]]["outgoing_observed_sender_transactions"]["status"], "unavailable")
        zero = binder_transactions_summary([start, finish, outside], iteration, rows)
        self.assertEqual(zero["recorded_binder_coverage"]["valid_positive_destination_events"], 1)
        self.assertEqual(zero["processes"][PROCESSES[0]]["outgoing_observed_sender_transactions"]["rate_per_second"], 0)
        invalid_only = line(101, "500002.200000001", "binder_transaction: dest_proc=0")
        self.assertEqual(binder_transactions_summary([start, invalid_only, finish], iteration, rows)["status"], "unavailable")

        unknown = line(9999, "500002.100000001", "binder_transaction: transaction=3 dest_proc=999")
        partial = binder_transactions_summary([start, valid, unknown, finish], iteration, rows)
        self.assertIn("unmapped", partial["event_evidence"][1]["reasons"][0])
        self.assertEqual(partial["processes"][PROCESSES[0]]["outgoing_observed_sender_transactions"]["count"], 1)
        for destination in ("0", "-1", "999oops", "999 dest_proc=998"):
            with self.subTest(destination=destination):
                malformed = line(101, "500002.200000001", f"binder_transaction: dest_proc={destination}")
                result = binder_transactions_summary([start, valid, malformed, finish], iteration, rows)
                metric = result["processes"][PROCESSES[0]]["outgoing_observed_sender_transactions"]
                self.assertEqual(metric["status"], "unavailable")
                self.assertEqual(metric["partial_evidence"]["count"], 1)
                self.assertIn("dest_proc", result["event_evidence"][1]["reasons"][0])

        unhealthy_cases = [
            (iteration | {"captureCategories": ["sched"]}, rows, raw),
            (iteration, rows | {"legacy_ftrace_retention": {}}, raw),
            (iteration, rows | {"legacy_ftrace_retention": rows["legacy_ftrace_retention"] | {"reported_overwritten_entries": 1}}, raw),
            (iteration, rows | {"legacy_ftrace_retention": rows["legacy_ftrace_retention"] | {"header_body_consistent": False}}, raw),
            (iteration, rows | {"trace_health": [{"name": "mismatched_sched_switch_tids", "severity": "error", "value": 1}]}, raw),
            (iteration, rows | {"trace_health": [{"name": "ftrace_cpu_overrun", "severity": "data loss", "value": 1}]}, raw),
            (iteration, {}, raw),
            (iteration | {"markerCookie": 124}, rows, raw),
            (iteration | {"traceMarkerPid": 51}, rows, raw),
            (iteration | {"markerCookie": True}, rows, raw),
            (iteration, rows, [start, start, valid, finish]),
            (iteration, rows, [finish, valid, start]),
            (iteration, rows, [start, valid]),
            (iteration, rows, [start, valid, "broken: binder_transaction: dest_proc=999", finish]),
            (iteration | {"processTids": {"100": [101], "200": [101]}}, rows, raw),
        ]
        for changed_iteration, changed_rows, changed_raw in unhealthy_cases:
            with self.subTest(iteration=changed_iteration, rows=changed_rows, raw=changed_raw):
                result = binder_transactions_summary(changed_raw, changed_iteration, changed_rows)
                self.assertEqual(result["status"], "unavailable")
                metric = result["processes"][PROCESSES[0]]["outgoing_observed_sender_transactions"]
                self.assertEqual(metric["status"], "unavailable")
                self.assertNotIn("rate_per_second", metric)
                self.assertTrue(result["event_evidence"])


if __name__ == "__main__":
    unittest.main()
