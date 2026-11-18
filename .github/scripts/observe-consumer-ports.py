#!/usr/bin/env python3
# Copyright (c) 2026, Oracle and/or its affiliates.
# Licensed under the Universal Permissive License v 1.0 as shown at
# https://oss.oracle.com/licenses/upl.

"""Observe unmodified consumer builds; collect extra JVM data after join warnings."""
import datetime
import gzip
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import sys
import time

RUNNING = True
WARNING = re.compile(r"Delaying formation of a new cluster|unresponsive or non-Coherence process|attempting to join the cluster")
PROPERTY = re.compile(r"^(coherence\.|tangosol\.|consumer\.|java\.net\.|java\.version=|java\.home=|user\.dir=)")


def now():
    return datetime.datetime.now(datetime.timezone.utc).isoformat()


def execute(command, timeout=10):
    try:
        result = subprocess.run(command, capture_output=True, text=True, timeout=timeout)
        return {"command": command, "exit_code": result.returncode,
                "stdout": result.stdout, "stderr": result.stderr, "time": now()}
    except (OSError, subprocess.TimeoutExpired) as error:
        return {"command": command, "error": str(error), "time": now()}


def write_json(path, value):
    path.write_text(json.dumps(value, indent=2) + "\n")


def environment(root, output):
    allowed = ["GITHUB_REPOSITORY", "GITHUB_SHA", "GITHUB_RUN_ID", "GITHUB_RUN_ATTEMPT",
               "RUNNER_OS", "RUNNER_ARCH", "ImageOS", "ImageVersion", "JAVA_HOME", "TRIAL", "SOURCE_SHA", "HEAD_BRANCH"]
    result = {"time": now(), "environment": {key: os.environ.get(key) for key in allowed},
              "uname": execute(["uname", "-a"]),
              "java": execute([str(Path(os.environ["JAVA_HOME"]) / "bin/java"), "-version"])}
    for path in ["/etc/os-release", "/proc/sys/net/ipv4/ip_local_port_range", "/proc/sys/net/ipv4/ip_local_reserved_ports"]:
        try:
            result[path] = Path(path).read_text()
        except OSError as error:
            result[path] = str(error)
    write_json(output / "environment.json", result)


def capture_jvms(output):
    jcmd = str(Path(os.environ["JAVA_HOME"]) / "bin/jcmd")
    listing = execute([jcmd, "-l"])
    write_json(output / "jvms.json", listing)
    for line in listing.get("stdout", "").splitlines():
        parts = line.split(maxsplit=1)
        if not parts or not parts[0].isdigit() or "JCmd" in line:
            continue
        pid = parts[0]
        properties = execute([jcmd, pid, "VM.system_properties"])
        properties["stdout"] = "\n".join(value for value in properties.get("stdout", "").splitlines() if PROPERTY.match(value))
        write_json(output / (pid + "-selected-properties.json"), properties)
        write_json(output / (pid + "-threads.json"), execute([jcmd, pid, "Thread.print", "-l"]))


def watch(root, output):
    def stop(_signum, _frame):
        global RUNNING
        RUNNING = False
    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    targets = root / "coherence-spring-session-boot-starter/target/it"
    observed = set()
    with gzip.open(output / "socket-samples.log.gz", "wt", compresslevel=1) as samples:
        while RUNNING:
            sample = execute(["ss", "-H", "-a", "-n", "-t", "-u", "-p"], timeout=5)
            samples.write(json.dumps(sample) + "\n")
            samples.flush()
            for log in targets.glob("*/build.log"):
                if str(log) in observed:
                    continue
                try:
                    text = log.read_text(errors="replace")
                except OSError:
                    continue
                if not WARNING.search(text):
                    continue
                observed.add(str(log))
                capture = output / ("cluster-warning-" + log.parent.name)
                capture.mkdir(exist_ok=True)
                (capture / "build-at-warning.log").write_text(text)
                write_json(capture / "sockets.json", execute(["ss", "-H", "-a", "-n", "-t", "-u", "-p"]))
                # Attach only after a join warning, so this cannot cause the initial bind race.
                capture_jvms(capture)
            time.sleep(1)
    write_json(output / "observer-summary.json", {"finished": now(), "warning_logs": sorted(observed)})


def collect(root, output):
    base = root / "coherence-spring-session-boot-starter/target"
    copied = []
    patterns = ["it/*/build.log", "it/*/target/surefire-reports/*", "it/*/target/failsafe-reports/*",
                "invoker-reports/*", "consumer-evidence/*"]
    for pattern in patterns:
        for source in base.glob(pattern):
            if not source.is_file():
                continue
            relative = source.relative_to(base)
            destination = output / "reports" / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, destination)
            copied.append(str(relative))
    write_json(output / "collected-reports.json", {"time": now(), "files": sorted(copied)})


if __name__ == "__main__":
    mode, root_arg, output_arg = sys.argv[1:]
    root_path, output_path = Path(root_arg), Path(output_arg)
    output_path.mkdir(parents=True, exist_ok=True)
    {"environment": environment, "watch": watch, "collect": collect}[mode](root_path, output_path)
