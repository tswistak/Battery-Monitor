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

from pathlib import Path
import re
import unittest

import analyze


class TraceRetentionTest(unittest.TestCase):
    def retention(self, retained, written):
        return analyze.legacy_ftrace_retention([
            f"# entries-in-buffer/entries-written: {retained}/{written}   #P:2",
            "app-100 [000] .... 1.002000: sched_switch: prev_pid=100 next_pid=0",
            "root-200 [001] .... 1.003000: tracing_mark_write: F|50|section|1",
        ])

    def test_reported_overwrite_gates_only_scheduler_cpu(self):
        retention = self.retention(2, 5)
        self.assertEqual(retention["classification"], "reported_overwrite")
        self.assertEqual(retention["reported_overwritten_entries"], 3)
        rows = {"legacy_ftrace_retention": retention, "trace_health": [],
                "cpu": [{"cpu_ms": 2}], "proc_cpu": {"cpu_ms": 3},
                "gfxinfo": {"frames": 4}, "am_start": {"total_ms": 5}, "window": [1]}
        analyze.gate_scheduler_cpu(rows, ("cpu",))
        self.assertEqual(rows["cpu"]["status"], "unavailable")
        self.assertEqual(rows["cpu"]["untrusted_estimates"], [{"cpu_ms": 2}])
        self.assertEqual(rows["proc_cpu"], {"cpu_ms": 3})
        self.assertEqual(rows["gfxinfo"], {"frames": 4})
        self.assertEqual(rows["am_start"], {"total_ms": 5})
        self.assertEqual(rows["window"], [1])

    def test_inconsistent_header_does_not_hide_reported_loss_or_claim_its_location(self):
        retention = self.retention(1, 2)
        self.assertFalse(retention["header_body_consistent"])
        self.assertEqual(retention["classification"], "reported_overwrite_with_header_body_inconsistency")
        rows = {"legacy_ftrace_retention": retention, "trace_health": [], "cpu": [{"cpu_ms": 2}]}
        analyze.gate_scheduler_cpu(rows, ("cpu",))
        self.assertEqual(rows["cpu"]["status"], "unavailable")
        self.assertEqual(rows["scheduler_cpu_warning"]["reported_ftrace_buffer_loss"], retention)

    def test_zero_reported_loss_keeps_scheduler_values_and_missing_header_is_explicit(self):
        retention = self.retention(2, 2)
        self.assertEqual(retention["classification"], "no_reported_overwrite")
        rows = {"legacy_ftrace_retention": retention, "trace_health": [], "cpu": [{"cpu_ms": 0}]}
        analyze.gate_scheduler_cpu(rows, ("cpu",))
        self.assertEqual(rows["cpu"], [{"cpu_ms": 0}])
        self.assertEqual(analyze.legacy_ftrace_retention([])["status"], "unavailable")

    def test_only_the_three_long_scenarios_receive_the_larger_buffer(self):
        source = (Path(__file__).resolve().parents[2] / "macrobenchmark/src/main/kotlin/codes/swistak/"
                  "batterymonitor/macrobenchmark/LegacyAtraceBenchmark.kt").read_text()
        self.assertIn("private const val DEFAULT_TRACE_BUFFER_KB = 8192", source)
        self.assertIn("private const val LONG_SCENARIO_TRACE_BUFFER_KB = 16384", source)
        mapping = re.search(r"private fun traceBufferSizeKb\(scenario: String\): Int = when \(scenario\) \{([^}]+)}", source)
        self.assertIsNotNone(mapping)
        self.assertEqual(set(re.findall(r'"([A-Za-z]+)"', mapping[1])),
                         {"settingsAlarmsAndUnsavedDraft", "drawerFirstAndRepeatedNavigation",
                          "historyRangesTabsAndSameLevelInsertion"})
        self.assertIn("else -> DEFAULT_TRACE_BUFFER_KB", mapping[1])
        self.assertIn("startTrace(session, scenario)", source)


if __name__ == "__main__":
    unittest.main()
