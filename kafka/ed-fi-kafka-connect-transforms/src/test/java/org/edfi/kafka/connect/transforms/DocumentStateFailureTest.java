// SPDX-License-Identifier: Apache-2.0
// Licensed to the Ed-Fi Alliance under one or more agreements.
// The Ed-Fi Alliance licenses this file to you under the Apache License, Version 2.0.
// See the LICENSE and NOTICES files in the project root for more information.

package org.edfi.kafka.connect.transforms;

import java.util.HashMap;
import java.util.Map;

import org.apache.kafka.common.config.ConfigException;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.errors.DataException;
import org.apache.kafka.connect.source.SourceRecord;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.entry;

class DocumentStateFailureTest {

    private static final String POSTGRESQL_SOURCE_SCHEMA = "io.debezium.connector.postgresql.Source";

    @Test
    void Given_Invalid_Configuration_Should_Use_ConfigException() {
        final Map<String, Object> config = validConfig();
        config.put(DocumentState.PROVIDER_CONFIG, "PostgreSQL");

        assertThatThrownBy(() -> new DocumentState<SourceRecord>().configure(config))
                .isInstanceOf(ConfigException.class)
                .isNotInstanceOf(DocumentState.TransformationFailureException.class);
    }

    @Test
    void Given_Malformed_Retained_Record_Should_Expose_Reason_And_Bounded_Metadata() {
        final Throwable thrown = catchThrowable(() -> configuredTransform()
                .classify(record(POSTGRESQL_SOURCE_SCHEMA, "public", "DocumentCache", "c")));

        assertThat(thrown)
                .isInstanceOf(DataException.class)
                .isInstanceOf(DocumentState.TransformationFailureException.class)
                .hasMessageContaining("reasonCode=UNSUPPORTED_SOURCE_SCHEMA")
                .hasMessageContaining("sourceSchema=public");

        final DocumentState.TransformationFailureException exception =
                (DocumentState.TransformationFailureException) thrown;
        assertThat(exception.reason()).isEqualTo(DocumentState.FailureReason.UNSUPPORTED_SOURCE_SCHEMA);
        assertThat(exception.metadata()).containsExactly(
                entry("provider", DocumentState.POSTGRESQL_PROVIDER),
                entry("sourceTopic", "server.dms.DocumentCache"),
                entry("sourceCategory", "RELATIONAL"),
                entry("sourceSchema", "public"),
                entry("sourceTable", "DocumentCache"));
    }

    @Test
    void Given_Unknown_Operation_Code_Should_Expose_Operation_Metadata() {
        final Throwable thrown = catchThrowable(() -> configuredTransform()
                .classify(record(POSTGRESQL_SOURCE_SCHEMA, "dms", "DocumentCache", "x")));

        final DocumentState.TransformationFailureException exception =
                (DocumentState.TransformationFailureException) thrown;
        assertThat(exception.reason()).isEqualTo(DocumentState.FailureReason.UNKNOWN_OPERATION_CODE);
        assertThat(exception.metadata()).containsEntry("operation", "x");
        assertThat(exception.getMessage())
                .contains("reasonCode=UNKNOWN_OPERATION_CODE")
                .contains("operation=x");
    }

    @Test
    void Given_Retained_Record_Failure_Should_Not_Include_Unbounded_Payload_Metadata() {
        final String sensitivePayload = "{\"DocumentJson\":{\"password\":\"super-secret\"}}";
        final String longTopic = "server.dms.DocumentProjectionWork.with.control\ncharacters."
                + "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx";

        final Throwable thrown = catchThrowable(() -> configuredTransform()
                .classify(record(longTopic, POSTGRESQL_SOURCE_SCHEMA, "dms", "DocumentProjectionWork", "c",
                        sensitivePayload)));

        final DocumentState.TransformationFailureException exception =
                (DocumentState.TransformationFailureException) thrown;
        assertThat(exception.reason()).isEqualTo(DocumentState.FailureReason.UNEXPECTED_RETAINED_SOURCE_TABLE);
        assertThat(exception.metadata()).containsOnlyKeys(
                "provider", "sourceTopic", "sourceCategory", "sourceSchema", "sourceTable");
        assertThat(exception.metadata().get("sourceTopic"))
                .doesNotContain("\n")
                .hasSizeLessThanOrEqualTo(128);
        assertThat(exception.metadata().values()).noneMatch(value -> value.contains("super-secret"));
        assertThat(exception.getMessage())
                .doesNotContain("DocumentJson")
                .doesNotContain("super-secret");
    }

    private static DocumentState<SourceRecord> configuredTransform() {
        final DocumentState<SourceRecord> transform = new DocumentState<>();
        transform.configure(validConfig());
        return transform;
    }

    private static Map<String, Object> validConfig() {
        final Map<String, Object> config = new HashMap<>();
        config.put(DocumentState.PROVIDER_CONFIG, DocumentState.POSTGRESQL_PROVIDER);
        config.put(DocumentState.TARGET_TOPIC_CONFIG, "edfi.documents");
        config.put(DocumentState.PROGRESS_TOPIC_CONFIG, "edfi.documents.cdc-progress");
        return config;
    }

    private static SourceRecord record(
            final String sourceSchemaName,
            final String sourceSchema,
            final String sourceTable,
            final String operation) {
        return record("server.dms.DocumentCache", sourceSchemaName, sourceSchema, sourceTable, operation, null);
    }

    private static SourceRecord record(
            final String topic,
            final String sourceSchemaName,
            final String sourceSchema,
            final String sourceTable,
            final String operation,
            final String payload) {
        final Schema sourceStructSchema = sourceSchema(sourceSchemaName);
        final SchemaBuilder valueSchemaBuilder = SchemaBuilder.struct()
                .field("source", sourceStructSchema)
                .field("op", Schema.STRING_SCHEMA);
        if (payload != null) {
            valueSchemaBuilder.field("after", Schema.STRING_SCHEMA);
        }

        final Schema valueSchema = valueSchemaBuilder.build();
        final Struct value = new Struct(valueSchema)
                .put("source", source(sourceStructSchema, sourceSchema, sourceTable))
                .put("op", operation);
        if (payload != null) {
            value.put("after", payload);
        }
        return new SourceRecord(sourcePartition(), sourceOffset(), topic, null, null, null, valueSchema, value);
    }

    private static Schema sourceSchema(final String sourceSchemaName) {
        return SchemaBuilder.struct()
                .name(sourceSchemaName)
                .field("schema", Schema.STRING_SCHEMA)
                .field("table", Schema.STRING_SCHEMA)
                .build();
    }

    private static Struct source(final Schema sourceSchema, final String relationalSchema, final String sourceTable) {
        return new Struct(sourceSchema)
                .put("schema", relationalSchema)
                .put("table", sourceTable);
    }

    private static Map<String, String> sourcePartition() {
        return Map.of("server", "dms");
    }

    private static Map<String, Long> sourceOffset() {
        return Map.of("position", 1L);
    }
}
