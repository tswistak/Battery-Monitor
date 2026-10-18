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

"""Install only isolated profiling APKs and retain a reproducible benchmark run."""

import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import uuid

from capture import PACKAGE, command, snapshot, verify_device, write_json

BENCHMARK_PACKAGE = "codes.swistak.batterymonitor.macrobenchmark"
METHOD_TIMEOUT_SECONDS = 900
CLASS_TIMEOUT_SECONDS = 7200
TEST_CLASS = f"{BENCHMARK_PACKAGE}.BatteryPerformanceBenchmark"
RUNNER = f"{BENCHMARK_PACKAGE}/androidx.test.runner.AndroidJUnitRunner"
BENCHMARK_SOURCE = (Path(__file__).resolve().parents[2] / "macrobenchmark/src/main/kotlin/codes/swistak/"
                    "batterymonitor/macrobenchmark/BatteryPerformanceBenchmark.kt")


def validate_config(config):
    if set(config) != {"anchorMillis", "iterations", "privileged", "monitoring"}:
        raise ValueError("Config must contain exactly anchorMillis, iterations, privileged, monitoring")
    if type(config["anchorMillis"]) is not int or config["anchorMillis"] <= 0:
        raise ValueError("anchorMillis must be a positive integer epoch in milliseconds")
    if type(config["iterations"]) is not int or not 1 <= config["iterations"] <= 50:
        raise ValueError("iterations must be an integer from 1 to 50")
    if any(type(config[key]) is not bool for key in ("privileged", "monitoring")):
        raise ValueError("privileged and monitoring must be JSON booleans")
    return config


def validate_method(method, source):
    methods = set(re.findall(r"@Test\s+fun\s+([A-Za-z][A-Za-z0-9_]*)\s*\(", source))
    if method is not None and (not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]*", method) or method not in methods):
        raise ValueError(f"Unknown benchmark method: {method}; available: {sorted(methods)}")
    return method


def shizuku_arguments(method, phase, enable, reader_checks, privileged, legacy_atrace):
    methods = {"shizukuStatus": "status", "shizukuMonitoringAcquisition": "measurement"}
    if method not in methods:
        if phase is not None or enable or reader_checks:
            raise ValueError("Shizuku options require a selected Shizuku probe method")
        return {}
    if not legacy_atrace:
        raise ValueError("Shizuku probes require --legacy-atrace to bypass the Perfetto rule; they start no trace")
    if method == "shizukuMonitoringAcquisition" and not privileged:
        raise ValueError("Shizuku acquisition requires privileged=true in the saved config")
    if method == "shizukuStatus" and (enable or reader_checks):
        raise ValueError("Shizuku status is observational; enable/reader checks require acquisition")
    phase = methods[method] if phase is None else phase
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,48}", phase):
        raise ValueError("Shizuku phase must contain 1 to 48 ASCII letters, digits, underscores or hyphens")
    return {"shizukuPhase": phase, "shizukuEnable": str(enable).lower(),
            "shizukuReaderChecks": str(reader_checks).lower()}


def validate_apk(aapt, path, expected_package):
    badging = subprocess.run(["rtk", "proxy", aapt, "dump", "badging", str(path)],
                             capture_output=True, text=True, timeout=60, check=True).stdout
    match = re.search(r"(?m)^package: name='([^']+)'", badging)
    if match is None or match[1] != expected_package:
        raise ValueError(f"Refusing APK {path}: expected package {expected_package}")
    if expected_package == PACKAGE and "application-debuggable" in badging:
        raise ValueError("The tested app must be non-debuggable")
    manifest = subprocess.run(["rtk", "proxy", aapt, "dump", "xmltree", str(path), "AndroidManifest.xml"],
                              capture_output=True, text=True, timeout=60, check=True).stdout
    if "android:sharedUserId" in manifest:
        raise ValueError("Refusing an APK with a sharedUserId")
    for process in re.findall(r'android:process[^\n]*?="([^"]+)"', manifest):
        if not (process.startswith(":") or process == expected_package or process.startswith(expected_package + ":")):
            raise ValueError(f"Refusing a fixed process outside {expected_package}: {process}")
    elements = []
    for line in manifest.splitlines():
        element = re.match(r"(\s*)E: ([^ (]+)", line)
        if element:
            indent = len(element[1])
            while elements and elements[-1][0] >= indent:
                elements.pop()
            elements.append((indent, element[2]))
        authority = re.search(r'android:authorities[^\n]*?="([^"]+)"', line)
        if authority and [name for _, name in elements][-2:] == ["application", "provider"]:
            for name in authority[1].split(";"):
                if name != expected_package and not name.startswith(expected_package + "."):
                    raise ValueError(f"Refusing a provider authority outside {expected_package}: {name}")
    return {"path": str(path.resolve()), "package": expected_package,
            "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
            "badging": badging, "manifest": manifest}


def instrumentation_passed(exit_code, text):
    success = re.search(r"(?m)^OK \(([1-9][0-9]*) tests?\)\s*$", text)
    failure = re.search(r"(?m)^(FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED|"
                        r"INSTRUMENTATION_RESULT: shortMsg=|INSTRUMENTATION_STATUS_CODE: -[12]\b|Error:)", text)
    return exit_code == 0 and success is not None and failure is None


def verify_installed_apk(device, apk, output):
    located = device("shell", "pm", "path", apk["package"], check=False)
    paths = re.findall(r"(?m)^package:(.+)$", located.stdout)
    if located.returncode or len(paths) != 1 or not re.fullmatch(r"/data/app/[-A-Za-z0-9_+./=~]+/base\.apk", paths[0].strip()):
        raise RuntimeError(f"Cannot verify one installed base APK for {apk['package']}")
    path = paths[0].strip()
    hashed = device("shell", "sha256sum", path, check=False)
    match = re.match(r"([0-9a-fA-F]{64})\s", hashed.stdout) if hashed.returncode == 0 else None
    if match:
        digest = match[1].lower()
        source = "device sha256sum"
    else:
        copied = output / f"installed-code-{apk['package']}.apk"
        pulled = device("pull", path, copied, check=False)
        if pulled.returncode or not copied.is_file():
            raise RuntimeError(f"Cannot read installed code APK for {apk['package']}")
        digest = hashlib.sha256(copied.read_bytes()).hexdigest()
        source = "pulled code APK"
    if digest != apk["sha256"]:
        raise RuntimeError(f"Installed APK checksum differs for {apk['package']}")
    return {"package": apk["package"], "device_path": path, "sha256": digest, "source": source}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", required=True)
    parser.add_argument("--aapt", required=True, help="Existing Android SDK aapt executable")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--app-apk", required=True, type=Path)
    parser.add_argument("--benchmark-apk", required=True, type=Path)
    parser.add_argument("--config", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path, help="New local directory")
    parser.add_argument("--method", help="One existing @Test method for smoke/retries; otherwise entire class")
    parser.add_argument("--use-installed", action="store_true", help="Verify installed code hashes instead of installing APKs")
    parser.add_argument("--legacy-atrace", action="store_true", help="Use explicit native ATrace/gfxinfo fallback instead of Macrobenchmark")
    parser.add_argument("--shizuku-phase", help="Retained phase label for a selected Shizuku probe; no fixture reset")
    parser.add_argument("--shizuku-enable", action="store_true", help="Explicitly enable Access for Shizuku acquisition warmup")
    parser.add_argument("--shizuku-reader-checks", action="store_true", help="Run separate production reader checks during Shizuku acquisition")
    args = parser.parse_args()
    config = validate_config(json.loads(args.config.read_text()))
    validate_method(args.method, BENCHMARK_SOURCE.read_text())
    probe_arguments = shizuku_arguments(args.method, args.shizuku_phase, args.shizuku_enable,
                                       args.shizuku_reader_checks, config["privileged"], args.legacy_atrace)
    identity = verify_device(args.adb, args.serial)
    if config["privileged"] and identity["api"] != "33":
        parser.error("Privileged scenarios are allowed only on the rooted OnePlus 2")
    if args.legacy_atrace and identity["api"] != "33":
        parser.error("Rooted legacy ATrace is allowed only on the OnePlus 2")
    apks = [validate_apk(args.aapt, args.app_apk, PACKAGE),
            validate_apk(args.aapt, args.benchmark_apk, BENCHMARK_PACKAGE)]
    args.output.mkdir(parents=True, exist_ok=False)
    write_json(args.output / "run-config.json", config)
    write_json(args.output / "apks.json", apks)
    records = []

    def device(*arguments, check=True):
        argv = command(args.adb, args.serial, *map(str, arguments))
        result = subprocess.run(argv, capture_output=True, text=True, timeout=180)
        record = {"command": argv, "exit_code": result.returncode,
                  "stdout": result.stdout, "stderr": result.stderr}
        records.append(record)
        write_json(args.output / "commands.json", records)
        if check and result.returncode:
            raise RuntimeError(f"Command failed; inspect {args.output / 'commands.json'}")
        return result

    remote = f"/sdcard/Android/media/{BENCHMARK_PACKAGE}/performance/{uuid.uuid4().hex}"
    result = {"serial": args.serial, "identity": identity, "remote_output": remote,
              "method": args.method, "fullTracing": identity["api"] == "33" and not args.legacy_atrace,
              "measurementBackend": "legacyAtrace" if args.legacy_atrace else "macrobenchmark", "passed": False,
              "timeout_seconds": METHOD_TIMEOUT_SECONDS if args.method else CLASS_TIMEOUT_SECONDS,
              "use_installed": args.use_installed}
    if probe_arguments:
        result["probe"] = {"kind": "shizuku", "probeStatusOnly": args.method == "shizukuStatus",
                           "instrumentationArguments": probe_arguments,
                           "fixtureResetRequested": False, "noATraceCaptured": True,
                           "successScope": "Status report delivered; availability must be read from its JSON"
                           if args.method == "shizukuStatus" else
                           "Actual acquisition and any requested reader checks; inspect retained JSON"}
    try:
        for apk in apks:
            verify_device(args.adb, args.serial)
            if args.use_installed:
                verified = verify_installed_apk(device, apk, args.output)
                result.setdefault("installed_code", []).append(verified)
                continue
            if identity["api"] == "29":
                remote_apk = f"/data/local/tmp/battery-profile-{apk['sha256'][:16]}.apk"
                device("push", apk["path"], remote_apk)
                verify_device(args.adb, args.serial)
                installed = device("shell", "pm", "install", "-r", remote_apk, check=False)
            else:
                installed = device("install", "-r", apk["path"], check=False)
            if installed.returncode or not re.search(r"(?m)^Success\s*$", installed.stdout):
                raise RuntimeError(f"Installation failed; inspect {args.output / 'commands.json'}")
        write_json(args.output / "conditions-before.json", snapshot(args.adb, args.serial))
        verify_device(args.adb, args.serial)
        device("shell", "mkdir", "-p", remote)
        arguments = ["shell", "am", "instrument", "-w", "-e", "class",
                     TEST_CLASS + (f"#{args.method}" if args.method else "")]
        for key, value in config.items():
            arguments += ["-e", key, str(value).lower()]
        for key, value in probe_arguments.items():
            arguments += ["-e", key, value]
        arguments += ["-e", "androidx.benchmark.fullTracing.enable", str(result["fullTracing"]).lower(),
                      "-e", "measurementBackend", result["measurementBackend"],
                      "-e", "additionalTestOutputDir", remote, RUNNER]
        verify_device(args.adb, args.serial)
        argv = command(args.adb, args.serial, *arguments)
        result["instrumentation_command"] = argv
        write_json(args.output / "run-result.json", result)
        print(f"Benchmark running on {args.serial}; output: {args.output / 'instrumentation.txt'}", flush=True)
        try:
            with (args.output / "instrumentation.txt").open("x") as output:
                measured = subprocess.run(argv, stdout=output, stderr=subprocess.STDOUT, text=True,
                                          timeout=result["timeout_seconds"])
            result["instrumentation_exit_code"] = measured.returncode
            result["passed"] = instrumentation_passed(measured.returncode, (args.output / "instrumentation.txt").read_text())
        except subprocess.TimeoutExpired:
            result.update({"timed_out": True, "status": "unavailable", "instrumentation_exit_code": None,
                           "reason": f"Instrumentation exceeded {result['timeout_seconds']} seconds"})
            try:
                verify_device(args.adb, args.serial)
                device("shell", "am", "force-stop", BENCHMARK_PACKAGE)
            except Exception as error:
                result["stop_error"] = str(error)
    except Exception as error:
        result["error"] = str(error)
        raise
    finally:
        try:
            write_json(args.output / "conditions-after.json", snapshot(args.adb, args.serial))
            verify_device(args.adb, args.serial)
            pulled = device("pull", remote, args.output / "benchmark", check=False)
            result["pull_exit_code"] = pulled.returncode
        except Exception as error:
            result["collection_error"] = str(error)
        write_json(args.output / "run-result.json", result)
    if not result["passed"] or result.get("pull_exit_code") != 0:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
