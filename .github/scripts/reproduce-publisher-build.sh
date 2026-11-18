#!/usr/bin/env bash
# Copyright (c) 2026, Oracle and/or its affiliates.
# Licensed under the Universal Permissive License v 1.0 as shown at
# https://oss.oracle.com/licenses/upl.

set -euo pipefail

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
diagnostic_dir="${RUNNER_TEMP:?}/publisher-port-repro"
mkdir -p "$diagnostic_dir"
observer_pid=''
reports_collected=false
stop_observer() {
  if [[ -n "$observer_pid" ]]; then
    kill "$observer_pid" 2>/dev/null || true
    wait "$observer_pid" 2>/dev/null || true
    observer_pid=''
  fi
}
collect_reports() {
  python3 "$script_dir/observe-consumer-ports.py" collect "$PWD" "$diagnostic_dir"
  reports_collected=true
}
cleanup() {
  stop_observer
  if [[ "$reports_collected" == false ]]; then collect_reports || true; fi
}
trap cleanup EXIT

git rev-parse HEAD | tee "$diagnostic_dir/checkout.txt"
[[ "$(git rev-parse HEAD)" == "$SOURCE_SHA" ]]
git diff --exit-code "$SOURCE_SHA" HEAD
python3 "$script_dir/observe-consumer-ports.py" environment "$PWD" "$diagnostic_dir"

version=$(./mvnw help:evaluate --no-transfer-progress -B --settings .mvn/settings.xml -N -q -Dexpression=project.version -DforceStdout)
printf '%s\n' "$version" | tee "$diagnostic_dir/root-version.txt"
[[ "$version" == '5.0.0-SNAPSHOT' ]]
./mvnw enforcer:enforce --no-transfer-progress -B --settings .mvn/settings.xml -Denforcer.rules=requireSnapshotVersion -Denforcer.ignoreCache=true -Denforcer.skip=false -Denforcer.fail=true 2>&1 | tee "$diagnostic_dir/snapshot-preflight.log"

git config --global user.email 'github-action@users.noreply.github.com'
git config --global user.name 'GitHub Action'
python3 "$script_dir/observe-consumer-ports.py" watch "$PWD" "$diagnostic_dir" &
observer_pid=$!
set +e
./mvnw clean install --no-transfer-progress -B --settings .mvn/settings.xml -DskipTests=true 2>&1 | tee "$diagnostic_dir/maven-clean-install.log"
pipeline_status=("${PIPESTATUS[@]}")
maven_exit=${pipeline_status[0]}
tee_exit=${pipeline_status[1]}
set -e
printf '%s\n' "$maven_exit" > "$diagnostic_dir/maven-exit-code.txt"
printf '%s\n' "$tee_exit" > "$diagnostic_dir/log-capture-exit-code.txt"
stop_observer
collect_reports
if [[ "$maven_exit" -eq 0 && "$tee_exit" -ne 0 ]]; then exit "$tee_exit"; fi
exit "$maven_exit"
