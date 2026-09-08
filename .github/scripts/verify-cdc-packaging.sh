#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Licensed to the Ed-Fi Alliance under one or more agreements.
# The Ed-Fi Alliance licenses this file to you under the Apache License, Version 2.0.
# See the LICENSE and NOTICES files in the project root for more information.
set -euo pipefail
image=${1:?Supply the candidate image reference}
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
container="cdc-exporter-smoke-$(cat /proc/sys/kernel/random/uuid)"
trap 'docker rm -f "$container" >/dev/null 2>&1 || true' EXIT
# The version, checksum, fixed mapping and default activation must be in the built image.
docker run --rm --entrypoint sh "$image" -ec '
  echo "0315f3f657876302c6205a98d4036ec775dca529c5d0419ca60ee669c688239f  /opt/edfi-cdc/jmx_prometheus_javaagent-1.5.0.jar" | sha256sum -c -
  test "$KAFKA_OPTS" = "-javaagent:/opt/edfi-cdc/jmx_prometheus_javaagent-1.5.0.jar=9404:/opt/edfi-cdc/cdc.yaml"
  test -r /opt/edfi-cdc/cdc.yaml
  test -f /kafka/libs/connect-api-4.3.0.jar
'
docker run --detach --name "$container" -p 127.0.0.1::9404 \
  -v "$root/kafka/ci-smoke/CdcExporterSmoke.java:/tmp/CdcExporterSmoke.java:ro" \
  --entrypoint sh "$image" -ec 'exec java $KAFKA_OPTS /tmp/CdcExporterSmoke.java' >/dev/null
port=$(docker port "$container" 9404/tcp | cut -d: -f2)
for _ in {1..60}; do
  if metrics=$(curl --fail --silent --max-time 2 "http://127.0.0.1:$port/metrics"); then
    if printf '%s\n' "$metrics" | grep -qx '# TYPE edfi_cdc_worker_start_time_seconds gauge' \
      && printf '%s\n' "$metrics" | grep -qx '# TYPE edfi_cdc_worker_heap_max_bytes gauge' \
      && printf '%s\n' "$metrics" | grep -Eq '^jmx_scrape_error 0(\.0)?$'; then
      echo 'Exporter packaging smoke passed: standard agent, JVM identity, heap and successful scrape.'
      exit 0
    fi
  fi
  sleep 1
done
echo 'Exporter packaging smoke failed; metrics payload omitted.' >&2
exit 1
