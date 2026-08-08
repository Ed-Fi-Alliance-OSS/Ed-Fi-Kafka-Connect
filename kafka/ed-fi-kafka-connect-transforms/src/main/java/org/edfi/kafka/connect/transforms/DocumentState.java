// SPDX-License-Identifier: Apache-2.0
// Licensed to the Ed-Fi Alliance under one or more agreements.
// The Ed-Fi Alliance licenses this file to you under the Apache License, Version 2.0.
// See the LICENSE and NOTICES files in the project root for more information.

package org.edfi.kafka.connect.transforms;

import java.util.Map;

import org.apache.kafka.common.config.AbstractConfig;
import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.common.config.ConfigException;
import org.apache.kafka.connect.connector.ConnectRecord;
import org.apache.kafka.connect.errors.DataException;
import org.apache.kafka.connect.transforms.Transformation;

public class DocumentState<R extends ConnectRecord<R>> implements Transformation<R> {

    public static final String PROVIDER_CONFIG = "provider";
    public static final String TARGET_TOPIC_CONFIG = "target.topic";
    public static final String PROGRESS_TOPIC_CONFIG = "progress.topic";

    public static final String POSTGRESQL_PROVIDER = "postgresql";
    public static final String SQLSERVER_PROVIDER = "sqlserver";

    private static final String PROGRESS_TOPIC_SUFFIX = ".cdc-progress";

    private static final ConfigDef.Validator PROVIDER_VALIDATOR = (name, value) -> {
        if (!(POSTGRESQL_PROVIDER.equals(value) || SQLSERVER_PROVIDER.equals(value))) {
            throw new ConfigException(name, value, "must be exactly 'postgresql' or 'sqlserver'");
        }
    };

    private static final ConfigDef.Validator NON_EMPTY_STRING = (name, value) -> {
        if (!(value instanceof String) || ((String) value).isEmpty()) {
            throw new ConfigException(name, value, "must be a non-empty string");
        }
    };

    public static final ConfigDef CONFIG_DEF = new ConfigDef()
            .define(PROVIDER_CONFIG, ConfigDef.Type.STRING, ConfigDef.NO_DEFAULT_VALUE,
                    PROVIDER_VALIDATOR, ConfigDef.Importance.HIGH,
                    "Relational source provider. Must be exactly 'postgresql' or 'sqlserver'.")
            .define(TARGET_TOPIC_CONFIG, ConfigDef.Type.STRING, ConfigDef.NO_DEFAULT_VALUE,
                    NON_EMPTY_STRING, ConfigDef.Importance.HIGH,
                    "Public document topic.")
            .define(PROGRESS_TOPIC_CONFIG, ConfigDef.Type.STRING, ConfigDef.NO_DEFAULT_VALUE,
                    NON_EMPTY_STRING, ConfigDef.Importance.HIGH,
                    "Progress topic, derived as target.topic plus '.cdc-progress'.");

    private Settings settings;

    @Override
    public ConfigDef config() {
        return CONFIG_DEF;
    }

    @Override
    public void configure(final Map<String, ?> configs) {
        validateRawProvider(configs);

        final AbstractConfig parsed = new AbstractConfig(CONFIG_DEF, configs);
        final String targetTopic = parsed.getString(TARGET_TOPIC_CONFIG);
        final String progressTopic = parsed.getString(PROGRESS_TOPIC_CONFIG);

        if (!progressTopic.equals(targetTopic + PROGRESS_TOPIC_SUFFIX)) {
            throw new ConfigException(PROGRESS_TOPIC_CONFIG, progressTopic,
                    "must equal target.topic plus '" + PROGRESS_TOPIC_SUFFIX + "'");
        }

        this.settings = new Settings(providerFrom(parsed.getString(PROVIDER_CONFIG)), targetTopic, progressTopic);
    }

    @Override
    public R apply(final R record) {
        throw new DataException("DocumentState record transformation is not implemented");
    }

    @Override
    public void close() {
    }

    Settings settings() {
        return settings;
    }

    private static void validateRawProvider(final Map<String, ?> configs) {
        final Object provider = configs.get(PROVIDER_CONFIG);
        if (!(POSTGRESQL_PROVIDER.equals(provider) || SQLSERVER_PROVIDER.equals(provider))) {
            throw new ConfigException(PROVIDER_CONFIG, provider, "must be exactly 'postgresql' or 'sqlserver'");
        }
    }

    private static Provider providerFrom(final String provider) {
        if (POSTGRESQL_PROVIDER.equals(provider)) {
            return Provider.POSTGRESQL;
        }
        if (SQLSERVER_PROVIDER.equals(provider)) {
            return Provider.SQLSERVER;
        }
        throw new ConfigException(PROVIDER_CONFIG, provider, "must be exactly 'postgresql' or 'sqlserver'");
    }

    enum Provider {
        POSTGRESQL,
        SQLSERVER
    }

    static final class Settings {
        private final Provider provider;
        private final String targetTopic;
        private final String progressTopic;

        Settings(final Provider provider, final String targetTopic, final String progressTopic) {
            this.provider = provider;
            this.targetTopic = targetTopic;
            this.progressTopic = progressTopic;
        }

        Provider provider() {
            return provider;
        }

        String targetTopic() {
            return targetTopic;
        }

        String progressTopic() {
            return progressTopic;
        }
    }
}
