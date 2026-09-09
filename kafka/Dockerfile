# SPDX-License-Identifier: Apache-2.0
# Licensed to the Ed-Fi Alliance under one or more agreements.
# The Ed-Fi Alliance licenses this file to you under the Apache License, Version 2.0.
# See the LICENSE and NOTICES files in the project root for more information.

FROM gradle:8.2.1-jdk-focal@sha256:89b352a0d0d40bb7294cbe5ffd4d9a8824cac6174f9ecc786c0d53ef4663863f AS build
COPY --chown=gradle:gradle /ed-fi-kafka-connect-transforms /home/gradle/src
WORKDIR /home/gradle/src
RUN gradle installDist partitionerJar --no-daemon

FROM quay.io/debezium/connect:3.6.0.Final@sha256:698f0559e667a242f962221079e75917b2b7a3ad4de62661e977628da0e33b45
LABEL maintainer="Ed-Fi Alliance, LLC and Contributors <techsupport@ed-fi.org>"

ARG opensearch=opensearch-connector-for-apache-kafka-3.1.0.tar
ARG elasticsearch=elasticsearch-connector-for-apache-kafka-7.0.0.tar

ADD --chown=kafka --chmod=600 https://github.com/aiven/opensearch-connector-for-apache-kafka/releases/download/v3.1.0/${opensearch} \
    /kafka/connect/

ADD --chown=kafka --chmod=600 https://github.com/Aiven-Open/elasticsearch-connector-for-apache-kafka/releases/download/v7.0.0/${elasticsearch} \
    /kafka/connect/

ARG iamauth=2.2.0

ADD --chown=kafka --chmod=600 https://github.com/aws/aws-msk-iam-auth/releases/download/v${iamauth}/aws-msk-iam-auth-${iamauth}-all.jar \
    /kafka/libs

WORKDIR /kafka/libs
SHELL ["/bin/bash", "-o", "pipefail", "-c"]
RUN originalSha="735dd058b81d088e767770d6cc4e7f1a1beea815ced18b66a23b35fd87651e77" && \
    newSha=$(sha256sum aws-msk-iam-auth-${iamauth}-all.jar | awk '{print $1}') && \
    if [ "$originalSha" != "$newSha" ]; then exit 1; fi

WORKDIR /kafka/connect/
SHELL ["/bin/bash", "-o", "pipefail", "-c"]
RUN originalSha="58c27bdb0b8883e2e3291a2aaa42151b77240a33d3361ad620c656e775da14d2" && \
    newSha=$(sha256sum ${opensearch} | awk '{print $1}') && \
    if [ "$originalSha" != "$newSha" ]; then exit 1; fi

RUN originalEsSha="396b28251e636f0ed1721a51c771adc26a78a2f332091af7b6f3182c91f20d3b" && \
    newEsSha=$(sha256sum ${elasticsearch} | awk '{print $1}') && \
    if [ "$originalEsSha" != "$newEsSha" ]; then exit 1; fi

RUN tar -xvf ${opensearch} && \
    rm ${opensearch}

RUN tar -xvf ${elasticsearch} && \
    rm ${elasticsearch}

# Named context support https://github.com/hadolint/hadolint/issues/830
# hadolint ignore=DL3022
COPY --from=parentdir LICENSE /kafka/connect/EDFI_LICENSE
# Named context support https://github.com/hadolint/hadolint/issues/830
# hadolint ignore=DL3022
COPY --from=parentdir NOTICES.md /kafka/connect/EDFI_NOTICES.md

COPY --from=build /home/gradle/src/build/libs /kafka/connect/ed-fi-kafka-connect-transforms

# Kafka producer client overrides, such as producer.override.partitioner.class, are loaded
# from the worker/client classpath rather than the isolated Connect plugin path.
COPY --from=build /home/gradle/src/build/partitioner/*.jar /kafka/libs/

# Standard exporter pinned independently of the Debezium base. The immutable mapping
# lives outside /kafka/config, which existing deployments can mount as a volume.
COPY --chown=kafka --chmod=444 metrics/exporter.env /opt/edfi-cdc/exporter.env
COPY --chown=kafka --chmod=444 metrics/cdc.yaml /opt/edfi-cdc/cdc.yaml
RUN chmod 755 /opt/edfi-cdc && source /opt/edfi-cdc/exporter.env && \
    jar="/opt/edfi-cdc/jmx_prometheus_javaagent-${JMX_EXPORTER_VERSION}.jar" && \
    curl --fail --silent --show-error --location --proto '=https' \
        "https://github.com/prometheus/jmx_exporter/releases/download/${JMX_EXPORTER_VERSION}/jmx_prometheus_javaagent-${JMX_EXPORTER_VERSION}.jar" \
        --output "$jar" && \
    echo "${JMX_EXPORTER_SHA256}  ${jar}" | sha256sum --check && \
    chmod 444 "$jar"
COPY --chown=kafka --chmod=555 cdc-entrypoint.sh /opt/edfi-cdc/entrypoint.sh
ENTRYPOINT ["/opt/edfi-cdc/entrypoint.sh"]
CMD ["start"]
EXPOSE 9404
