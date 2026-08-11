// SPDX-License-Identifier: Apache-2.0
// Licensed to the Ed-Fi Alliance under one or more agreements.
// The Ed-Fi Alliance licenses this file to you under the Apache License, Version 2.0.
// See the LICENSE and NOTICES files in the project root for more information.

package org.edfi.kafka.connect.transforms;

import org.apache.kafka.common.config.ConfigException;

final class DocumentStateConfig {
    static void validateProgressTopic(final String targetTopic, final String progressTopic) {
        if (!progressTopic.equals(targetTopic + DocumentState.PROGRESS_TOPIC_SUFFIX)) {
            throw new ConfigException(DocumentState.PROGRESS_TOPIC_CONFIG, progressTopic,
                    "must equal target.topic plus '" + DocumentState.PROGRESS_TOPIC_SUFFIX + "'");
        }
    }

    static void validateProviderConfig(final String name, final Object value) {
        if (!(DocumentState.POSTGRESQL_PROVIDER.equals(value) || DocumentState.SQLSERVER_PROVIDER.equals(value))) {
            throw new ConfigException(name, value, "must be exactly 'postgresql' or 'sqlserver'");
        }
    }

    static void validateNonEmptyStringConfig(final String name, final Object value) {
        if (!(value instanceof String) || ((String) value).isEmpty()) {
            throw new ConfigException(name, value, "must be a non-empty string");
        }
    }

    static void validateRawProviderConfig(final Object provider) {
        if (provider instanceof String && !provider.equals(((String) provider).trim())) {
            throw new ConfigException(
                    DocumentState.PROVIDER_CONFIG, provider, "must be exactly 'postgresql' or 'sqlserver'");
        }
    }
}
