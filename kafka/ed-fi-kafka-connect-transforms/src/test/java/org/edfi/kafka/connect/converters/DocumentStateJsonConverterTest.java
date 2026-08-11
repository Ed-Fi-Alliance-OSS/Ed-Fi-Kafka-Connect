// SPDX-License-Identifier: Apache-2.0
// Licensed to the Ed-Fi Alliance under one or more agreements.
// The Ed-Fi Alliance licenses this file to you under the Apache License, Version 2.0.
// See the LICENSE and NOTICES files in the project root for more information.

package org.edfi.kafka.connect.converters;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.stream.Stream;

import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaAndValue;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.errors.DataException;
import org.apache.kafka.connect.storage.Converter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentStateJsonConverterTest {

    private static final String TOPIC = "edfi.documents";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void Given_Exact_Public_Handshake_Should_Return_Defensive_Copy() {
        final DocumentStateJsonConverter converter = configuredConverter();
        final byte[] value = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);

        final byte[] converted = converter.fromConnectData(
                TOPIC, DocumentStateJsonConverter.publicValueSchema(), value);

        assertThat(converted).isNotSameAs(value);
        assertThat(converted).containsExactly(value);
        value[0] = '[';
        assertThat(new String(converted, StandardCharsets.UTF_8)).isEqualTo("{\"a\":1}");
    }

    @ParameterizedTest
    @MethodSource("invalidPublicHandshakes")
    void Given_Reserved_Public_Schema_Name_With_Invalid_Handshake_Should_Fail(
            final Schema schema,
            final Object value) {
        final DocumentStateJsonConverter converter = configuredConverter();

        assertThatThrownBy(() -> converter.fromConnectData(TOPIC, schema, value))
                .isInstanceOf(DataException.class)
                .hasMessageContaining(DocumentStateJsonConverter.PUBLIC_SCHEMA_NAME);
    }

    @Test
    void Given_Public_Tombstone_Should_Delegate_As_Kafka_Null() {
        final DocumentStateJsonConverter converter = configuredConverter();

        assertThat(converter.fromConnectData(TOPIC, null, null)).isNull();
    }

    @Test
    void Given_NonPublic_Record_Should_Delegate_To_JsonConverter() throws Exception {
        final DocumentStateJsonConverter converter = configuredConverter();

        final byte[] converted = converter.fromConnectData(TOPIC, Schema.STRING_SCHEMA, "progress");

        final JsonNode node = MAPPER.readTree(converted);
        assertThat(node.asText()).isEqualTo("progress");
    }

    @Test
    void Given_Reverse_Conversion_Should_Delegate_To_JsonConverter() {
        final DocumentStateJsonConverter converter = configuredConverter();
        final byte[] value = "{\"progress\":true}".getBytes(StandardCharsets.UTF_8);

        final SchemaAndValue converted = converter.toConnectData(TOPIC, value);

        assertThat(converted.value()).isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) converted.value()).get("progress")).isEqualTo(true);
    }

    @Test
    void Given_ServiceLoader_Should_Load_DocumentStateJsonConverter() {
        final boolean loaded = ServiceLoader.load(Converter.class)
                .stream()
                .anyMatch(provider -> provider.type().equals(DocumentStateJsonConverter.class));

        assertThat(loaded).isTrue();
    }

    private static Stream<Object[]> invalidPublicHandshakes() {
        return Stream.of(
                invalidPublicHandshake(
                        SchemaBuilder.string()
                                .name(DocumentStateJsonConverter.PUBLIC_SCHEMA_NAME)
                                .version(DocumentStateJsonConverter.PUBLIC_SCHEMA_VERSION)
                                .build(),
                        "not-bytes"),
                invalidPublicHandshake(
                        SchemaBuilder.bytes()
                                .name(DocumentStateJsonConverter.PUBLIC_SCHEMA_NAME)
                                .version(DocumentStateJsonConverter.PUBLIC_SCHEMA_VERSION)
                                .optional()
                                .build(),
                        new byte[] {1}),
                invalidPublicHandshake(
                        SchemaBuilder.bytes()
                                .name(DocumentStateJsonConverter.PUBLIC_SCHEMA_NAME)
                                .version(2)
                                .build(),
                        new byte[] {1}),
                invalidPublicHandshake(
                        DocumentStateJsonConverter.publicValueSchema(),
                        "not-bytes"),
                invalidPublicHandshake(
                        DocumentStateJsonConverter.publicValueSchema(),
                        null));
    }

    private static Object[] invalidPublicHandshake(final Schema schema, final Object value) {
        return new Object[] {schema, value};
    }

    private static DocumentStateJsonConverter configuredConverter() {
        final DocumentStateJsonConverter converter = new DocumentStateJsonConverter();
        converter.configure(Map.of(
                "schemas.enable", "false",
                "decimal.format", "NUMERIC"), false);
        return converter;
    }
}
