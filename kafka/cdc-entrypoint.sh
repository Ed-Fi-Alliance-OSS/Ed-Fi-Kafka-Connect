#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Licensed to the Ed-Fi Alliance under one or more agreements.
# The Ed-Fi Alliance licenses this file to you under the Apache License, Version 2.0.
# See the LICENSE and NOTICES files in the project root for more information.
set -euo pipefail
# shellcheck source=metrics/exporter.env
source /opt/edfi-cdc/exporter.env

# Compose at startup so deployment-supplied JVM options cannot replace the required
# CDC exporter. Debezium's own switch would add a second exporter on the same port.
if [[ ${KAFKA_OPTS:-} == *-javaagent:*jmx_prometheus_javaagent* ]]; then
    echo 'The CDC image supplies its JMX exporter; remove the exporter agent from KAFKA_OPTS.' >&2
    exit 1
fi
export KAFKA_OPTS="${KAFKA_OPTS:+$KAFKA_OPTS }-javaagent:/opt/edfi-cdc/jmx_prometheus_javaagent-${JMX_EXPORTER_VERSION}.jar=9404:/opt/edfi-cdc/cdc.yaml"
export ENABLE_JMX_EXPORTER=false
exec /docker-entrypoint.sh "$@"
