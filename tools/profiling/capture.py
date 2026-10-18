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

"""Record local evidence from an explicitly targeted, verified profiling phone."""

import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import shlex
import subprocess
import uuid

PACKAGE = "codes.swistak.batterymonitor.profile"
PROCESSES = (PACKAGE, f"{PACKAGE}:BIS")


def command(adb, serial, *arguments):
    return ["rtk", "proxy", adb, "-s", serial, *arguments]


def read_device(adb, serial, *arguments):
    argv = command(adb, serial, *arguments)
    try:
        result = subprocess.run(argv, capture_output=True, text=True, timeout=30)
        return {"command": argv, "exit_code": result.returncode,
                "stdout": result.stdout.strip(), "stderr": result.stderr.strip()}
    except subprocess.TimeoutExpired:
        return {"command": argv, "exit_code": None, "error": "timeout after 30 seconds"}


def verify_device(adb, serial):
    identity = {}
    for key, arguments in {
        "state": ("get-state",),
        "model": ("shell", "getprop", "ro.product.model"),
        "device": ("shell", "getprop", "ro.product.device"),
        "product": ("shell", "getprop", "ro.product.name"),
        "api": ("shell", "getprop", "ro.build.version.sdk"),
    }.items():
        result = read_device(adb, serial, *arguments)
        if result.get("exit_code") != 0:
            raise RuntimeError(f"Cannot verify {serial}: {result}")
        identity[key] = result["stdout"]
    if identity["state"] != "device" or not is_supported_device(identity):
        raise RuntimeError(f"Refusing to profile a different or unauthorized device: {identity}")
    return identity


def is_supported_device(identity):
    oneplus2 = (identity.get("device", "").lower() == "oneplus2"
            and identity.get("model", "").upper() in {"ONE A2001", "ONE A2003", "ONE A2005"}
            and "oneplus2" in identity.get("product", "").lower()
            and identity.get("api") == "33")
    redmi7a = (identity.get("device", "").lower() == "pine"
               and identity.get("model", "").lower() == "redmi 7a"
               and "pine" in identity.get("product", "").lower()
               and identity.get("api") == "29")
    return oneplus2 or redmi7a


def snapshot(adb, serial):
    commands = {
        "battery": ("dumpsys", "battery"),
        "thermal": ("dumpsys", "thermalservice"),
        "hardware_properties": ("dumpsys", "hardware_properties"),
        "power": ("dumpsys", "power"),
        "package_and_compilation": ("dumpsys", "package", PACKAGE),
        "processes": ("ps", "-A", "-o", "USER,PID,PPID,NAME,ARGS"),
        "atrace_categories": ("atrace", "--list_categories"),
        "perfetto_sources": ("perfetto", "--query"),
        "android_version": ("getprop", "ro.build.version.release"),
        "android_api": ("getprop", "ro.build.version.sdk"),
        "fingerprint": ("getprop", "ro.build.fingerprint"),
        "device_wall_clock": ("date", "-u"),
        "uptime": ("cat", "/proc/uptime"),
        "kernel": ("uname", "-a"),
        "font_scale": ("settings", "get", "system", "font_scale"),
        "locale": ("getprop", "persist.sys.locale"),
        "window_animation_scale": ("settings", "get", "global", "window_animation_scale"),
        "transition_animation_scale": ("settings", "get", "global", "transition_animation_scale"),
        "animator_duration_scale": ("settings", "get", "global", "animator_duration_scale"),
        "stay_on_while_plugged_in": ("settings", "get", "global", "stay_on_while_plugged_in"),
    }
    for index, process in enumerate(PROCESSES):
        commands[f"meminfo_{index}"] = ("dumpsys", "meminfo", process)
    identity = verify_device(adb, serial)
    observations = {key: read_device(adb, serial, "shell", *args) for key, args in commands.items()}
    power = observations["power"]
    if power.get("exit_code") == 0:
        fields = r"mWakefulness=|mIsPowered=|mPlugType=|mStayOn=|mStayOnWhilePluggedInSetting="
        power["stdout"] = "\n".join(line.strip() for line in power["stdout"].splitlines()
                                      if re.search(fields, line))
        power["filter"] = fields
    return {
        "host_time_utc": datetime.now(timezone.utc).isoformat(),
        "serial": serial,
        "identity": identity,
        "processes": PROCESSES,
        "observations": observations,
    }


def config(duration_seconds):
    events = ("sched/sched_switch", "sched/sched_waking", "sched/sched_wakeup_new",
              "sched/sched_process_fork", "sched/sched_process_exec", "sched/sched_process_exit",
              "task/task_newtask", "task/task_rename", "power/cpu_frequency", "power/cpu_idle")
    categories = ("am", "wm", "gfx", "view", "input", "dalvik", "database")
    lines = [
        "buffers { size_kb: 65536 fill_policy: DISCARD }",
        f"duration_ms: {duration_seconds * 1000}",
        "flush_period_ms: 5000",
        'data_sources { config { name: "linux.ftrace" ftrace_config {',
        *[f'  ftrace_events: "{event}"' for event in events],
        *[f'  atrace_categories: "{category}"' for category in categories],
        *[f'  atrace_apps: "{process}"' for process in PROCESSES],
        "} } }",
        'data_sources { config { name: "linux.process_stats" process_stats_config {',
        "  scan_all_processes_on_start: true proc_stats_poll_ms: 1000",
        "} } }",
        'data_sources { config { name: "linux.sys_stats" sys_stats_config {',
        "  stat_period_ms: 1000 stat_counters: STAT_CPU_TIMES",
        "  meminfo_period_ms: 1000 meminfo_counters: MEMINFO_MEM_AVAILABLE",
        "} } }",
        'data_sources { config { name: "android.surfaceflinger.frametimeline" } }',
    ]
    return "\n".join(lines) + "\n"


def write_json(path, value):
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False) + "\n")


def cleanup_remote_file(adb, serial, path):
    allowed = (r"/data/local/tmp/battery-monitor-profile-[0-9a-f]{32}\.pbtxt|"
               r"/data/misc/perfetto-traces/battery-monitor-profile-[0-9a-f]{32}\.pftrace")
    if not re.fullmatch(allowed, path):
        raise ValueError("Refusing to remove a path outside this capture's temporary file namespace")
    verify_device(adb, serial)
    return read_device(adb, serial, "shell", "rm", "--", path)


def diagnostic_error(path):
    with path.open("rb") as output:
        prefix = output.read(4096)
    plain = re.sub(rb"\x1b\[[0-?]*[ -/]*[@-~]", b"", prefix).lower()
    if b"perfetto_cmd.cc" in plain and any(message in plain for message in
                                           (b"could not open", b"permission denied", b"failed", b"error")):
        return "Perfetto reported error diagnostics; inspect the retained command output"
    return None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", required=True, help="Existing Android SDK adb executable")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--output", type=Path, required=True, help="New local directory")
    parser.add_argument("--seconds", type=int, default=30)
    parser.add_argument("--snapshot-only", action="store_true")
    args = parser.parse_args()
    if not 1 <= args.seconds <= 300:
        parser.error("--seconds must be between 1 and 300")
    verify_device(args.adb, args.serial)
    args.output.mkdir(parents=True, exist_ok=False)
    write_json(args.output / "conditions-before.json", snapshot(args.adb, args.serial))
    if args.snapshot_only:
        return
    trace_config = config(args.seconds)
    local_config = args.output / "config.pbtxt"
    local_config.write_text(trace_config)
    capture_id = uuid.uuid4().hex
    remote_config = f"/data/local/tmp/battery-monitor-profile-{capture_id}.pbtxt"
    remote_trace = f"/data/misc/perfetto-traces/battery-monitor-profile-{capture_id}.pftrace"
    pipeline = f"cat {shlex.quote(remote_config)} | perfetto --txt -c - -o {shlex.quote(remote_trace)}"
    argv = command(args.adb, args.serial, "exec-out", pipeline)
    print(f"Recording {args.seconds}s for {args.serial} into {args.output}", flush=True)
    trace_path = args.output / "system.perfetto-trace"
    evidence = {"command": argv, "remote_config": remote_config, "remote_trace": remote_trace,
                "status": "unavailable"}
    try:
        verify_device(args.adb, args.serial)
        pushed = read_device(args.adb, args.serial, "push", str(local_config), remote_config)
        evidence["config_push"] = pushed
        if pushed.get("exit_code") != 0:
            raise RuntimeError("Unable to upload this capture's temporary Perfetto config")
        verify_device(args.adb, args.serial)
        stdout_path = args.output / "perfetto.stdout.txt"
        stderr_path = args.output / "perfetto.stderr.txt"
        with stdout_path.open("xb") as stdout, stderr_path.open("xb") as stderr:
            result = subprocess.run(argv, stdout=stdout, stderr=stderr, timeout=args.seconds + 60)
        evidence["exit_code"] = result.returncode
        output_error = diagnostic_error(stdout_path) or diagnostic_error(stderr_path)
        if output_error:
            raise RuntimeError(output_error)
        if result.returncode != 0:
            raise RuntimeError(f"Trace capture failed; inspect {args.output}")
    except subprocess.TimeoutExpired:
        evidence.update({"error": "capture timed out", "partial_trace": str(trace_path)})
        raise
    except Exception as error:
        evidence["error"] = str(error)
        raise
    finally:
        try:
            verify_device(args.adb, args.serial)
            evidence["trace_pull"] = read_device(args.adb, args.serial, "pull", remote_trace, str(trace_path))
        except Exception as error:
            evidence["trace_pull"] = {"status": "unavailable", "error": str(error)}
        pulled = evidence["trace_pull"].get("exit_code") == 0 and trace_path.is_file()
        evidence["remote_retained"] = []
        if pulled:
            for name, path in (("config_cleanup", remote_config), ("trace_cleanup", remote_trace)):
                try:
                    cleaned = cleanup_remote_file(args.adb, args.serial, path)
                except Exception as error:
                    cleaned = {"status": "unavailable", "error": str(error)}
                evidence[name] = cleaned
                if cleaned.get("exit_code") != 0:
                    evidence["remote_retained"].append(path)
        else:
            evidence["remote_retained"] = [remote_config, remote_trace]
            evidence.setdefault("error", "Unable to pull the trace; remote files retained")
        if trace_path.is_file():
            evidence.update({"size_bytes": trace_path.stat().st_size,
                             "sha256": hashlib.sha256(trace_path.read_bytes()).hexdigest()})
        if pulled and evidence.get("size_bytes", 0) == 0:
            evidence.setdefault("error", "Perfetto produced an empty trace")
        if pulled and evidence.get("size_bytes", 0) > 0 and "error" not in evidence:
            evidence["status"] = "captured"
        write_json(args.output / "capture.json", evidence)
        try:
            after = snapshot(args.adb, args.serial)
        except RuntimeError as error:
            after = {"status": "unavailable", "error": str(error)}
        write_json(args.output / "conditions-after.json", after)
    if evidence["status"] != "captured":
        raise RuntimeError(f"Trace capture unavailable; inspect {args.output}")


if __name__ == "__main__":
    main()
