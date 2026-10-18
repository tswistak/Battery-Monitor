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

import json
import unittest

from root_probe import parse_results, root_script


class RootProbeTest(unittest.TestCase):
    def records(self):
        return [{"record": "start", "context": "shell-root-app_process", "uid": 0, "pid": 321,
                 "api": 33, "iterations": 1},
                {"record": "measurement", "iteration": 0, "elapsed_nanos": 2_000_000,
                 "successful_root_snapshot": True, "snapshot": {"access_method": "root", "remote_uid": 0}},
                {"record": "end", "complete": True}]

    def parse(self, records):
        return parse_results("\n".join(map(json.dumps, records)), 1)

    def test_success_preserves_context_and_unavailable_command_count(self):
        result = self.parse(self.records())
        self.assertEqual(result["elapsed_millis"], [2.0])
        self.assertIsNone(result["command_count"])

    def test_rejects_wrong_identity_failed_partial_or_invalid_measurement(self):
        for field, value in (("uid", 2000), ("api", 29), ("iterations", 2), ("pid", 0)):
            records = self.records()
            records[0][field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                self.parse(records)
        for field, value in (("elapsed_nanos", -1), ("successful_root_snapshot", False), ("iteration", 1)):
            records = self.records()
            records[1][field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                self.parse(records)
        with self.assertRaises(ValueError):
            self.parse(self.records()[:-1])

    def test_script_is_bound_to_verified_hashes_and_owned_namespace(self):
        remote = "/data/local/tmp/battery-shell-probe-" + "a" * 32 + "/helper.jar"
        script = root_script("/data/app/~~x/pkg/base.apk", "b" * 64, remote, "c" * 64, 5)
        self.assertIn("exec app_process / codes.swistak.batterymonitor.profiling.ShellCollectorProbe 5", script)
        self.assertIn("b" * 64, script)
        for path in ("/sdcard/helper.jar", remote + "; id"):
            with self.assertRaises(ValueError):
                root_script("/data/app/~~x/pkg/base.apk", "b" * 64, path, "c" * 64, 5)


if __name__ == "__main__":
    unittest.main()
