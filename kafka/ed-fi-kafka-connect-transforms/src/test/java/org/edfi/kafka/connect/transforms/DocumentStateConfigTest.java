// SPDX-License-Identifier: Apache-2.0
// Licensed to the Ed-Fi Alliance under one or more agreements.
// The Ed-Fi Alliance licenses this file to you under the Apache License, Version 2.0.
// See the LICENSE and NOTICES files in the project root for more information.

package org.edfi.kafka.connect.transforms;

import java.util.HashMap;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.stream.Stream;

import org.apache.kafka.common.config.ConfigException;
import org.apache.kafka.connect.sink.SinkRecord;
import org.apache.kafka.connect.transforms.Transformation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentStateConfigTest {

    @ParameterizedTest
    @MethodSource("validProviderConfigs")
    void Given_Valid_Provider_Should_Configure(final String provider, final DocumentState.Provider expectedProvider) {
        final DocumentState<SinkRecord> transform = configuredTransform(provider);

        assertThat(transform.settings().provider()).isEqualTo(expectedProvider);
        assertThat(transform.settings().targetTopic()).isEqualTo("edfi.documents");
        assertThat(transform.settings().progressTopic()).isEqualTo("edfi.documents.cdc-progress");
    }

    @ParameterizedTest
    @MethodSource("invalidProviderConfigs")
    void Given_Invalid_Provider_Should_Throw_ConfigException(final Object provider) {
        final Map<String, Object> config = validConfig();
        config.put(DocumentState.PROVIDER_CONFIG, provider);

        assertThatThrownBy(() -> newTransform().configure(config)).isInstanceOf(ConfigException.class);
    }

    @Test
    void Given_Missing_Provider_Should_Throw_ConfigException() {
        final Map<String, Object> config = validConfig();
        config.remove(DocumentState.PROVIDER_CONFIG);

        assertThatThrownBy(() -> newTransform().configure(config)).isInstanceOf(ConfigException.class);
    }

    @Test
    void Given_Empty_TargetTopic_Should_Throw_ConfigException() {
        final Map<String, Object> config = validConfig();
        config.put(DocumentState.TARGET_TOPIC_CONFIG, "");

        assertThatThrownBy(() -> newTransform().configure(config)).isInstanceOf(ConfigException.class);
    }

    @Test
    void Given_Missing_TargetTopic_Should_Throw_ConfigException() {
        final Map<String, Object> config = validConfig();
        config.remove(DocumentState.TARGET_TOPIC_CONFIG);

        assertThatThrownBy(() -> newTransform().configure(config)).isInstanceOf(ConfigException.class);
    }

    @Test
    void Given_Missing_ProgressTopic_Should_Throw_ConfigException() {
        final Map<String, Object> config = validConfig();
        config.remove(DocumentState.PROGRESS_TOPIC_CONFIG);

        assertThatThrownBy(() -> newTransform().configure(config)).isInstanceOf(ConfigException.class);
    }

    @Test
    void Given_Empty_ProgressTopic_Should_Throw_ConfigException() {
        final Map<String, Object> config = validConfig();
        config.put(DocumentState.PROGRESS_TOPIC_CONFIG, "");

        assertThatThrownBy(() -> newTransform().configure(config)).isInstanceOf(ConfigException.class);
    }

    @Test
    void Given_ProgressTopic_Not_Derived_From_TargetTopic_Should_Throw_ConfigException() {
        final Map<String, Object> config = validConfig();
        config.put(DocumentState.PROGRESS_TOPIC_CONFIG, "edfi.documents.progress");

        assertThatThrownBy(() -> newTransform().configure(config)).isInstanceOf(ConfigException.class);
    }

    @Test
    void Given_ConfigDefinition_Should_Expose_Only_StoryOwned_Settings() {
        assertThat(newTransform().config().configKeys().keySet()).containsExactly(
                DocumentState.PROVIDER_CONFIG,
                DocumentState.TARGET_TOPIC_CONFIG,
                DocumentState.PROGRESS_TOPIC_CONFIG);
    }

    @Test
    void Given_ServiceLoader_Should_Load_DocumentState_Transform() {
        final boolean loaded = ServiceLoader.load(Transformation.class)
                .stream()
                .anyMatch(provider -> provider.type().equals(DocumentState.class));

        assertThat(loaded).isTrue();
    }

    private static Stream<Object[]> validProviderConfigs() {
        return Stream.of(
                new Object[] {DocumentState.POSTGRESQL_PROVIDER, DocumentState.Provider.POSTGRESQL},
                new Object[] {DocumentState.SQLSERVER_PROVIDER, DocumentState.Provider.SQLSERVER});
    }

    private static Stream<Object> invalidProviderConfigs() {
        return Stream.of("", "Postgresql", "postgres", " postgresql", "postgresql ", "SQLSERVER");
    }

    private DocumentState<SinkRecord> configuredTransform(final String provider) {
        final DocumentState<SinkRecord> transform = newTransform();
        final Map<String, Object> config = validConfig();
        config.put(DocumentState.PROVIDER_CONFIG, provider);
        transform.configure(config);
        return transform;
    }

    private DocumentState<SinkRecord> newTransform() {
        return new DocumentState<>();
    }

    private Map<String, Object> validConfig() {
        final Map<String, Object> config = new HashMap<>();
        config.put(DocumentState.PROVIDER_CONFIG, DocumentState.POSTGRESQL_PROVIDER);
        config.put(DocumentState.TARGET_TOPIC_CONFIG, "edfi.documents");
        config.put(DocumentState.PROGRESS_TOPIC_CONFIG, "edfi.documents.cdc-progress");
        return config;
    }
}
