#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Licensed to the Ed-Fi Alliance under one or more agreements.
# The Ed-Fi Alliance licenses this file to you under the Apache License, Version 2.0.
# See the LICENSE and NOTICES files in the project root for more information.
set -euo pipefail
image=${1:?Supply the candidate image reference}
evidence=${2:-}
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
# shellcheck source=../../kafka/metrics/exporter.env
source "$root/kafka/metrics/exporter.env"
container="cdc-exporter-smoke-$(cat /proc/sys/kernel/random/uuid)"
cleanup() {
  status=$?
  if (( status != 0 )); then
    echo 'Exporter packaging smoke failed; container state and recent logs follow.' >&2
    docker inspect --format '{{json .State}}' "$container" >&2 || true
    docker logs --tail 100 "$container" >&2 || true
  fi
  docker rm -f "$container" >/dev/null 2>&1 || true
}
trap cleanup EXIT

# Measure the candidate's actual jar and mapping, then compare against reviewed inputs.
metadata=$(docker run --rm --entrypoint sh "$image" -ec '
  . /opt/edfi-cdc/exporter.env
  test -f /kafka/libs/connect-api-4.3.0.jar
  jar_hash=$(sha256sum "/opt/edfi-cdc/jmx_prometheus_javaagent-${JMX_EXPORTER_VERSION}.jar")
  mapping_hash=$(sha256sum /opt/edfi-cdc/cdc.yaml)
  printf "%s %s %s\n" "$JMX_EXPORTER_VERSION" "${jar_hash%% *}" "${mapping_hash%% *}"
')
read -r image_version exporter_sha mapping_sha <<< "$metadata"
expected_mapping=$(sha256sum "$root/kafka/metrics/cdc.yaml")
if [[ $image_version != "$JMX_EXPORTER_VERSION" || $exporter_sha != "$JMX_EXPORTER_SHA256" \
   || $mapping_sha != "${expected_mapping%% *}" ]]; then
  echo 'Candidate exporter version, jar checksum or mapping differs from the reviewed inputs.' >&2
  exit 1
fi

# Exercise the real entrypoint, including runtime overrides and Debezium's exporter switch.
for mode in default runtime-options debezium-switch; do
  options=()
  smoke_args=()
  if [[ $mode != default ]]; then
    options+=(-e "KAFKA_OPTS=-Dedfi.smoke.option=$mode")
    smoke_args+=("$mode")
  fi
  if [[ $mode == debezium-switch ]]; then
    options+=(-e ENABLE_JMX_EXPORTER=true)
  fi
  docker run --detach --name "$container" -p 127.0.0.1::9404 \
    "${options[@]}" \
    -v "$root/kafka/ci-smoke/CdcExporterSmoke.java:/tmp/CdcExporterSmoke.java:ro" \
    "$image" sh -ec 'exec java $KAFKA_OPTS /tmp/CdcExporterSmoke.java "$@"' sh "${smoke_args[@]}" >/dev/null
  port=$(docker port "$container" 9404/tcp | cut -d: -f2)
  if [[ ! $port =~ ^[0-9]+$ ]]; then
    echo "No exporter port was published ($mode)." >&2
    exit 1
  fi
  ready=false
  deadline=$((SECONDS + 90))
  while (( SECONDS < deadline )); do
    if [[ $(docker inspect --format '{{.State.Running}}' "$container") != true ]]; then
      echo "Exporter container exited before readiness ($mode)." >&2
      exit 1
    fi
    if metrics=$(curl --fail --silent --max-time 2 "http://127.0.0.1:$port/metrics"); then
      if grep -qx '# TYPE edfi_cdc_worker_start_time_seconds gauge' <<< "$metrics" \
        && grep -qx '# TYPE edfi_cdc_worker_heap_max_bytes gauge' <<< "$metrics" \
        && grep -Eq '^jmx_scrape_error 0(\.0)?$' <<< "$metrics"; then
        ready=true
        break
      fi
    fi
    sleep 1
  done
  if [[ $ready != true ]]; then
    echo "Exporter did not become ready before the deadline ($mode)." >&2
    exit 1
  fi
  # Verify the JVM command line, not merely a responding endpoint.
  docker exec "$container" sh -ec '
    test "$(tr "\000" "\n" < /proc/1/cmdline | grep -c -- "^-javaagent:")" -eq 1
  '
  docker rm -f "$container" >/dev/null
  echo "Exporter packaging smoke passed ($mode)."
done

if [[ -n $evidence ]]; then
  mkdir -p "$(dirname "$evidence")"
  python3 - "$evidence" "$image_version" "$exporter_sha" "$mapping_sha" <<'PY'
import json, sys
with open(sys.argv[1], 'w') as output:
    json.dump(dict(exporterVersion=sys.argv[2], exporterSha256=sys.argv[3],
                   mappingSha256=sys.argv[4]), output, indent=2)
PY
fi
