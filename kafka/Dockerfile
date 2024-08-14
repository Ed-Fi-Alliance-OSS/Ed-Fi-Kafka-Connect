# SPDX-License-Identifier: Apache-2.0
# Licensed to the Ed-Fi Alliance under one or more agreements.
# The Ed-Fi Alliance licenses this file to you under the Apache License, Version 2.0.
# See the LICENSE and NOTICES files in the project root for more information.

FROM gradle:8.2.1-jdk-focal@sha256:78b95c865c1bcb50ced01501e6a9c6c9bcc181be93c1ea723ff4b58b2f7e9a96 AS build
COPY --chown=gradle:gradle /ed-fi-kafka-connect-transforms /home/gradle/src
WORKDIR /home/gradle/src
RUN gradle installDist --no-daemon

FROM debezium/connect:2.7.0.Final@sha256:9af456e88dcd60a16c8fabe69477118b10449b39654b17c7221a5ef82d17b884
LABEL maintainer="Ed-Fi Alliance, LLC and Contributors <techsupport@ed-fi.org>"

ARG opensearch=opensearch-connector-for-apache-kafka-3.1.0.tar

ADD --chown=kafka --chmod=600 https://github.com/aiven/opensearch-connector-for-apache-kafka/releases/download/v3.1.0/${opensearch} \
    /kafka/connect/

WORKDIR /kafka/connect/
SHELL ["/bin/bash", "-o", "pipefail", "-c"]
RUN originalSha="58c27bdb0b8883e2e3291a2aaa42151b77240a33d3361ad620c656e775da14d2" && \
    newSha=$(sha256sum ${opensearch} | awk '{print $1}') && \
    if [ "$originalSha" != "$newSha" ]; then exit 1; fi

RUN tar -xvf ${opensearch} && \
    rm ${opensearch}

COPY --from=build /home/gradle/src/build/libs /kafka/connect/ed-fi-kafka-connect-transforms

# Kafka connect SMT to expand JSON string: https://github.com/RedHatInsights/expandjsonsmt
ARG expandjsonsmt=kafka-connect-smt-expandjsonsmt-0.0.7.tar.gz
ADD --chown=kafka --chmod=600 https://github.com/RedHatInsights/expandjsonsmt/releases/download/0.0.7/${expandjsonsmt} \
    /kafka/connect/

RUN originalSha="47f470091a1102826966001d0095a4e419beee00a16c0e77b4108261d04faed0" && \
    newSha=$(sha256sum ${expandjsonsmt} | awk '{print $1}') && \
    if [ "$originalSha" != "$newSha" ]; then exit 1; fi

RUN tar -xvf ${expandjsonsmt} && \
    rm ${expandjsonsmt}
