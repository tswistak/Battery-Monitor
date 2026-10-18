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

"""Measure verified APK collector bytecode in Shell's existing root context, without app-UID grants."""

import argparse
import hashlib
import json
from pathlib import Path
import re
import shlex
import statistics
import subprocess
import uuid
import zipfile

from capture import PACKAGE, command, snapshot, verify_device, write_json
from run import validate_apk, verify_installed_apk

PROBE_CLASS = "codes.swistak.batterymonitor.profiling.ShellCollectorProbe"


def validate_helper(path):
    with zipfile.ZipFile(path) as archive:
        if archive.namelist() != ["classes.dex"] or not archive.read("classes.dex").startswith(b"dex\n"):
            raise ValueError("Expected the helper-only D8 jar containing exactly classes.dex")
    return hashlib.sha256(path.read_bytes()).hexdigest()


def root_script(installed_path, apk_sha256, remote_jar, helper_sha256, iterations):
    if not re.fullmatch(r"/data/app/[-A-Za-z0-9_+./=~]+/base\.apk", installed_path):
        raise ValueError("Expected one installed base APK")
    if not re.fullmatch(r"/data/local/tmp/battery-shell-probe-[0-9a-f]{32}/helper\.jar", remote_jar):
        raise ValueError("Refusing a helper outside the owned probe directory")
    if any(not re.fullmatch(r"[0-9a-f]{64}", digest) for digest in (apk_sha256, helper_sha256)):
        raise ValueError("Expected SHA-256 hashes")
    if type(iterations) is not int or not 1 <= iterations <= 50:
        raise ValueError("Expected 1 to 50 iterations")
    return ("set -eu\n"
            "[ \"$(id -u)\" = 0 ]\n"
            f"[ \"$(sha256sum {shlex.quote(installed_path)} | cut -d ' ' -f 1)\" = '{apk_sha256}' ]\n"
            f"[ \"$(sha256sum {shlex.quote(remote_jar)} | cut -d ' ' -f 1)\" = '{helper_sha256}' ]\n"
            f"export CLASSPATH={shlex.quote(remote_jar + ':' + installed_path)}\n"
            f"exec app_process / {PROBE_CLASS} {iterations}\n")


def parse_results(stdout, iterations):
    records = [json.loads(line) for line in stdout.splitlines() if line.strip()]
    if len(records) != iterations + 2:
        raise ValueError("Missing or unexpected probe records")
    start, *measurements, end = records
    if (start.get("record") != "start" or start.get("context") != "shell-root-app_process"
            or start.get("uid") != 0 or start.get("api") != 33 or start.get("iterations") != iterations
            or type(start.get("pid")) is not int or start["pid"] <= 0):
        raise ValueError("Probe identity does not match the authorized Shell-root context")
    for index, record in enumerate(measurements):
        if (record.get("record") != "measurement" or record.get("iteration") != index
                or type(record.get("elapsed_nanos")) is not int or record["elapsed_nanos"] < 0
                or record.get("successful_root_snapshot") is not True
                or not isinstance(record.get("snapshot"), dict)
                or record["snapshot"].get("access_method") != "root"
                or record["snapshot"].get("remote_uid") != 0):
            raise ValueError(f"Invalid or unsuccessful root measurement {index}")
    if end != {"record": "end", "complete": True}:
        raise ValueError("Probe did not finish successfully")
    times = [row["elapsed_nanos"] / 1_000_000 for row in measurements]
    return {"records": records, "elapsed_millis": times,
            "median_elapsed_millis": statistics.median(times),
            "command_count": None,
            "limitations": ["Shell-root context; app UID grant and Shizuku transport are not exercised",
                            "No application context; BatteryManager context-dependent fields are absent",
                            "First read includes lazy initialization; later reads reuse this process",
                            "Subprocess counts are not observed"]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", required=True)
    parser.add_argument("--aapt", required=True)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--app-apk", required=True, type=Path)
    parser.add_argument("--helper-jar", required=True, type=Path)
    parser.add_argument("--iterations", type=int, default=5)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    if not 1 <= args.iterations <= 50:
        parser.error("--iterations must be between 1 and 50")
    identity = verify_device(args.adb, args.serial)
    if identity["api"] != "33" or identity["device"].lower() != "oneplus2":
        parser.error("This probe is allowed only on the rooted OnePlus 2")
    apk = validate_apk(args.aapt, args.app_apk, PACKAGE)
    helper_hash = validate_helper(args.helper_jar)
    args.output.mkdir(parents=True, exist_ok=False)
    records = []

    def device(*arguments, check=True):
        argv = command(args.adb, args.serial, *map(str, arguments))
        result = subprocess.run(argv, capture_output=True, text=True, timeout=150)
        records.append({"command": argv, "exit_code": result.returncode,
                        "stdout": result.stdout, "stderr": result.stderr})
        write_json(args.output / "commands.json", records)
        if check and result.returncode:
            raise RuntimeError("Device command failed; inspect commands.json")
        return result

    remote = f"/data/local/tmp/battery-shell-probe-{uuid.uuid4().hex}"
    result = {"identity": identity, "serial": args.serial, "apk": apk,
              "helper_sha256": helper_hash, "remote_directory": remote,
              "context": "shell-root-app_process", "passed": False}
    created = False
    try:
        installed = verify_installed_apk(device, apk, args.output)
        result["installed_before"] = installed
        write_json(args.output / "conditions-before.json", snapshot(args.adb, args.serial))
        script = args.output / "run.sh"
        script.write_text(root_script(installed["device_path"], apk["sha256"],
                                      remote + "/helper.jar", helper_hash, args.iterations))
        verify_device(args.adb, args.serial)
        device("shell", "mkdir", remote)
        created = True
        device("push", args.helper_jar, remote + "/helper.jar")
        device("push", script, remote + "/run.sh")
        verify_device(args.adb, args.serial)
        probe_command = shlex.join(["toybox", "timeout", "120", "su", "-c",
                                    shlex.join(["sh", remote + "/run.sh"])])
        probe = device("shell", probe_command, check=False)
        (args.output / "probe.stdout.jsonl").write_text(probe.stdout)
        (args.output / "probe.stderr.txt").write_text(probe.stderr)
        result["probe_exit_code"] = probe.returncode
        if probe.returncode:
            raise RuntimeError("Collector probe failed; inspect its stdout and stderr")
        result["measurement"] = parse_results(probe.stdout, args.iterations)
        result["installed_after"] = verify_installed_apk(device, apk, args.output)
        result["passed"] = True
    except Exception as error:
        result["error"] = str(error)
        raise
    finally:
        try:
            verify_device(args.adb, args.serial)
            if created:
                device("shell", "rm", "--", remote + "/helper.jar", remote + "/run.sh")
                device("shell", "rmdir", remote)
                result["remote_cleanup"] = True
            write_json(args.output / "conditions-after.json", snapshot(args.adb, args.serial))
        except Exception as error:
            result["cleanup_error"] = str(error)
            result["passed"] = False
        write_json(args.output / "result.json", result)
    if not result["passed"]:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
