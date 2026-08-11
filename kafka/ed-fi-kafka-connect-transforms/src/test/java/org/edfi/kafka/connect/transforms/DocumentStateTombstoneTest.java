// SPDX-License-Identifier: Apache-2.0
// Licensed to the Ed-Fi Alliance under one or more agreements.
// The Ed-Fi Alliance licenses this file to you under the Apache License, Version 2.0.
// See the LICENSE and NOTICES files in the project root for more information.

package org.edfi.kafka.connect.transforms;

import java.util.stream.Stream;

import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.errors.DataException;
import org.apache.kafka.connect.source.SourceRecord;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class DocumentStateTombstoneTest {
    private static final String SQLSERVER_UNAVAILABLE_VALUE = "__debezium_unavailable_value";

    @ParameterizedTest
    @MethodSource("providers")
    void Given_Document_Delete_Should_Emit_Public_Tombstone_And_Strip_Metadata(final String provider) {
        final SourceRecord record = DocumentStateTestRecords.documentDeleteRecordWithPublicMetadata(
                provider, DocumentStateTestRecords.documentBeforeRow(provider, DocumentStateTestRecords.DOCUMENT_UUID));

        final SourceRecord result = DocumentStateTestRecords.configuredTransform(provider).apply(record);

        assertThat(result.topic()).isEqualTo(DocumentStateTestRecords.TARGET_TOPIC);
        assertThat(result.kafkaPartition()).isNull();
        assertThat(result.keySchema()).isSameAs(Schema.STRING_SCHEMA);
        assertThat(result.key()).isEqualTo(DocumentStateTestRecords.DOCUMENT_UUID);
        assertThat(result.valueSchema()).isNull();
        assertThat(result.value()).isNull();
        assertThat(result.timestamp()).isNull();
        assertThat(result.headers()).isEmpty();
        assertThat(result.sourcePartition()).isEqualTo(record.sourcePartition());
        assertThat(result.sourceOffset()).isEqualTo(record.sourceOffset());
    }

    @ParameterizedTest
    @MethodSource("documentDeletesWithoutAvailableBeforeDocumentUuid")
    void Given_Document_Delete_Without_Available_Before_DocumentUuid_Should_Not_Require_It(
            final String provider,
            final SourceRecord record) {
        final SourceRecord result = DocumentStateTestRecords.configuredTransform(provider).apply(record);

        assertThat(result.topic()).isEqualTo(DocumentStateTestRecords.TARGET_TOPIC);
        assertThat(result.key()).isEqualTo(DocumentStateTestRecords.DOCUMENT_UUID);
        assertThat(result.valueSchema()).isNull();
        assertThat(result.value()).isNull();
    }

    @Test
    void Given_Document_Delete_Before_DocumentUuid_Differs_From_Key_Should_Fail() {
        final SourceRecord record = DocumentStateTestRecords.documentDeleteRecord(
                DocumentState.POSTGRESQL_PROVIDER,
                DocumentStateTestRecords.documentBeforeRow(
                        DocumentState.POSTGRESQL_PROVIDER, DocumentStateTestRecords.OTHER_DOCUMENT_UUID));

        final Throwable thrown = catchThrowable(() -> DocumentStateTestRecords
                .configuredTransform(DocumentState.POSTGRESQL_PROVIDER)
                .apply(record));

        assertFailure(thrown, DocumentState.FailureReason.DOCUMENT_UUID_MISMATCH, "Document");
    }

    @Test
    void Given_Document_Delete_Before_Row_Field_Is_Required_Should_Fail() {
        final Schema beforeSchema = requiredPostgresqlDocumentBeforeRowSchema();
        final SourceRecord record = DocumentStateTestRecords.documentDeleteRecordWithBeforeSchema(
                DocumentState.POSTGRESQL_PROVIDER,
                beforeSchema,
                DocumentStateTestRecords.documentBeforeRow(
                        beforeSchema, DocumentStateTestRecords.DOCUMENT_UUID));

        final Throwable thrown = catchThrowable(() -> DocumentStateTestRecords
                .configuredTransform(DocumentState.POSTGRESQL_PROVIDER)
                .apply(record));

        assertFailure(thrown, DocumentState.FailureReason.UNSUPPORTED_RETAINED_ROW_SHAPE, "Document");
    }

    @Test
    void Given_Postgresql_Document_Delete_Before_DocumentUuid_Unavailable_Marker_Should_Fail() {
        final Schema beforeSchema = DocumentStateTestRecords.documentBeforeRowSchema(
                DocumentState.POSTGRESQL_PROVIDER,
                DocumentStateTestRecords.pinnedUuidSchema(DocumentState.POSTGRESQL_PROVIDER));
        final SourceRecord record = DocumentStateTestRecords.documentDeleteRecordWithBeforeSchema(
                DocumentState.POSTGRESQL_PROVIDER,
                beforeSchema,
                DocumentStateTestRecords.documentBeforeRow(beforeSchema, SQLSERVER_UNAVAILABLE_VALUE));

        final Throwable thrown = catchThrowable(() -> DocumentStateTestRecords
                .configuredTransform(DocumentState.POSTGRESQL_PROVIDER)
                .apply(record));

        assertFailure(thrown, DocumentState.FailureReason.INVALID_DOCUMENT_UUID, "Document");
    }

    @Test
    void Given_SqlServer_Document_Delete_Before_DocumentUuid_Unavailable_Marker_With_NonPinned_Shape_Should_Fail() {
        final Schema beforeSchema = DocumentStateTestRecords.documentBeforeRowSchema(
                DocumentState.SQLSERVER_PROVIDER,
                SchemaBuilder.string().name(DocumentStateTestRecords.POSTGRESQL_UUID_SCHEMA).build());
        final SourceRecord record = DocumentStateTestRecords.documentDeleteRecordWithBeforeSchema(
                DocumentState.SQLSERVER_PROVIDER,
                beforeSchema,
                DocumentStateTestRecords.documentBeforeRow(beforeSchema, SQLSERVER_UNAVAILABLE_VALUE));

        final Throwable thrown = catchThrowable(() -> DocumentStateTestRecords
                .configuredTransform(DocumentState.SQLSERVER_PROVIDER)
                .apply(record));

        assertFailure(thrown, DocumentState.FailureReason.UNSUPPORTED_DOCUMENT_UUID_SHAPE, "Document");
    }

    @Test
    void Given_SqlServer_Document_Delete_Before_DocumentUuid_Unavailable_Marker_With_Pinned_Shape_Should_Succeed() {
        final Schema beforeSchema = DocumentStateTestRecords.documentBeforeRowSchema(
                DocumentState.SQLSERVER_PROVIDER,
                DocumentStateTestRecords.pinnedUuidSchema(DocumentState.SQLSERVER_PROVIDER));
        final SourceRecord record = DocumentStateTestRecords.documentDeleteRecordWithBeforeSchema(
                DocumentState.SQLSERVER_PROVIDER,
                beforeSchema,
                DocumentStateTestRecords.documentBeforeRow(beforeSchema, SQLSERVER_UNAVAILABLE_VALUE));

        final SourceRecord result = DocumentStateTestRecords
                .configuredTransform(DocumentState.SQLSERVER_PROVIDER)
                .apply(record);

        assertThat(result.topic()).isEqualTo(DocumentStateTestRecords.TARGET_TOPIC);
        assertThat(result.key()).isEqualTo(DocumentStateTestRecords.DOCUMENT_UUID);
        assertThat(result.valueSchema()).isNull();
        assertThat(result.value()).isNull();
    }

    @ParameterizedTest
    @MethodSource("nonPinnedBeforeDocumentUuidShapes")
    void Given_Document_Delete_Before_DocumentUuid_Uses_NonPinned_Shape_Should_Fail(
            final String provider,
            final Schema beforeDocumentUuidSchema) {
        final Schema beforeSchema =
                DocumentStateTestRecords.documentBeforeRowSchema(provider, beforeDocumentUuidSchema);
        final SourceRecord record = DocumentStateTestRecords.documentDeleteRecordWithBeforeSchema(
                provider,
                beforeSchema,
                DocumentStateTestRecords.documentBeforeRow(beforeSchema, DocumentStateTestRecords.DOCUMENT_UUID));

        final Throwable thrown = catchThrowable(() -> DocumentStateTestRecords
                .configuredTransform(provider)
                .apply(record));

        assertFailure(thrown, DocumentState.FailureReason.UNSUPPORTED_DOCUMENT_UUID_SHAPE, "Document");
    }

    @ParameterizedTest
    @MethodSource("automaticDeleteTombstonesOnRecognizedSourceTopics")
    void Given_Automatic_Debezium_Delete_Tombstone_On_Recognized_Source_Topic_Should_Drop(
            final String provider,
            final String sourceTable) {
        final SourceRecord record =
                DocumentStateTestRecords.automaticDebeziumDeleteTombstone(provider, sourceTable);

        final SourceRecord result = DocumentStateTestRecords.configuredTransform(provider).apply(record);

        assertThat(result).isNull();
    }

    @Test
    void Given_Automatic_Debezium_Delete_Tombstone_With_Plain_String_Key_Should_Fail_Closed() {
        final SourceRecord automaticTombstone =
                DocumentStateTestRecords.automaticDebeziumDeleteTombstone(DocumentState.POSTGRESQL_PROVIDER);
        final SourceRecord record = recordWithKey(
                automaticTombstone, Schema.STRING_SCHEMA, DocumentStateTestRecords.DOCUMENT_UUID);

        final Throwable thrown = catchThrowable(() -> DocumentStateTestRecords
                .configuredTransform(DocumentState.POSTGRESQL_PROVIDER)
                .apply(record));

        assertFailure(thrown, DocumentState.FailureReason.UNSUPPORTED_DOCUMENT_KEY_SHAPE, "Document");
    }

    @Test
    void Given_Null_Value_Record_On_Different_Source_Server_Topic_Should_Fail_Closed() {
        final SourceRecord automaticTombstone =
                DocumentStateTestRecords.automaticDebeziumDeleteTombstone(DocumentState.POSTGRESQL_PROVIDER);
        final SourceRecord record = recordWithTopic(automaticTombstone, "someOtherServer.dms.Document");

        final Throwable thrown = catchThrowable(() -> DocumentStateTestRecords
                .configuredTransform(DocumentState.POSTGRESQL_PROVIDER)
                .apply(record));

        assertThat(thrown)
                .isInstanceOf(DataException.class)
                .isInstanceOf(DocumentState.TransformationFailureException.class);
        final DocumentState.TransformationFailureException exception =
                (DocumentState.TransformationFailureException) thrown;
        assertThat(exception.reason()).isEqualTo(DocumentState.FailureReason.MISSING_SOURCE_METADATA);
        assertThat(exception.metadata())
                .containsEntry("provider", DocumentState.POSTGRESQL_PROVIDER)
                .containsEntry("sourceTopic", "someOtherServer.dms.Document");
    }

    @Test
    void Given_Null_Value_Retained_Record_On_Unrecognized_Source_Topic_Should_Fail_Closed() {
        final SourceRecord automaticTombstone =
                DocumentStateTestRecords.automaticDebeziumDeleteTombstone(
                        DocumentState.POSTGRESQL_PROVIDER, "CdcHeartbeat");
        final SourceRecord record = new SourceRecord(
                automaticTombstone.sourcePartition(),
                automaticTombstone.sourceOffset(),
                automaticTombstone.topic(),
                automaticTombstone.kafkaPartition(),
                automaticTombstone.keySchema(),
                automaticTombstone.key(),
                null,
                null);

        final Throwable thrown = catchThrowable(() -> DocumentStateTestRecords
                .configuredTransform(DocumentState.POSTGRESQL_PROVIDER)
                .apply(record));

        assertThat(thrown)
                .isInstanceOf(DataException.class)
                .hasMessageContaining("missing source metadata")
                .hasMessageContaining("sourceTopic=server.dms.CdcHeartbeat");
    }

    @Test
    void Given_Null_Value_Retained_Record_On_Recognized_Source_Topic_Without_Key_Should_Fail_Closed() {
        final SourceRecord automaticTombstone =
                DocumentStateTestRecords.automaticDebeziumDeleteTombstone(DocumentState.POSTGRESQL_PROVIDER);
        final SourceRecord record = new SourceRecord(
                automaticTombstone.sourcePartition(),
                automaticTombstone.sourceOffset(),
                automaticTombstone.topic(),
                automaticTombstone.kafkaPartition(),
                null,
                null,
                null,
                null);

        final Throwable thrown = catchThrowable(() -> DocumentStateTestRecords
                .configuredTransform(DocumentState.POSTGRESQL_PROVIDER)
                .apply(record));

        assertFailure(thrown, DocumentState.FailureReason.MISSING_DOCUMENT_KEY, "Document");
    }

    @ParameterizedTest
    @MethodSource("cacheDropOperations")
    void Given_DocumentCache_Delete_Or_Truncate_Should_Not_Produce_Public_Tombstone(final String operation) {
        final SourceRecord result = DocumentStateTestRecords
                .configuredTransform(DocumentState.POSTGRESQL_PROVIDER)
                .apply(DocumentStateTestRecords.documentCacheDropRecord(
                        DocumentState.POSTGRESQL_PROVIDER, operation));

        assertThat(result).isNull();
    }

    private static Stream<String> providers() {
        return Stream.of(DocumentState.POSTGRESQL_PROVIDER, DocumentState.SQLSERVER_PROVIDER);
    }

    private static Stream<Object[]> documentDeletesWithoutAvailableBeforeDocumentUuid() {
        final Schema postgresqlBeforeSchema = DocumentStateTestRecords.documentBeforeRowSchema(
                DocumentState.POSTGRESQL_PROVIDER,
                DocumentStateTestRecords.pinnedUuidSchema(DocumentState.POSTGRESQL_PROVIDER));
        final Schema postgresqlNullableBeforeSchema = DocumentStateTestRecords.documentBeforeRowSchema(
                DocumentState.POSTGRESQL_PROVIDER,
                SchemaBuilder.string()
                        .name(DocumentStateTestRecords.POSTGRESQL_UUID_SCHEMA)
                        .version(DocumentStateTestRecords.DEBEZIUM_LOGICAL_SCHEMA_VERSION)
                        .optional()
                        .build());
        final Schema sqlServerBeforeSchema = DocumentStateTestRecords.documentBeforeRowSchema(
                DocumentState.SQLSERVER_PROVIDER,
                DocumentStateTestRecords.pinnedUuidSchema(DocumentState.SQLSERVER_PROVIDER));
        final Schema postgresqlBeforeWithoutDocumentUuid =
                DocumentStateTestRecords.documentBeforeRowSchemaWithoutDocumentUuid(
                        DocumentState.POSTGRESQL_PROVIDER);

        return Stream.of(
                recordWithoutBefore(DocumentState.POSTGRESQL_PROVIDER),
                recordWithNullBefore(DocumentState.POSTGRESQL_PROVIDER, postgresqlBeforeSchema),
                recordWithMissingBeforeDocumentUuid(DocumentState.POSTGRESQL_PROVIDER,
                        postgresqlBeforeWithoutDocumentUuid),
                recordWithNullBeforeDocumentUuid(DocumentState.POSTGRESQL_PROVIDER, postgresqlNullableBeforeSchema),
                recordWithUnavailableBeforeDocumentUuid(DocumentState.SQLSERVER_PROVIDER, sqlServerBeforeSchema));
    }

    private static Stream<Object[]> nonPinnedBeforeDocumentUuidShapes() {
        return Stream.of(
                new Object[] {DocumentState.POSTGRESQL_PROVIDER, Schema.STRING_SCHEMA},
                new Object[] {
                    DocumentState.POSTGRESQL_PROVIDER,
                    SchemaBuilder.string()
                            .name(DocumentStateTestRecords.POSTGRESQL_UUID_SCHEMA)
                            .version(2)
                            .build()
                },
                new Object[] {
                    DocumentState.SQLSERVER_PROVIDER,
                    SchemaBuilder.string().name(DocumentStateTestRecords.POSTGRESQL_UUID_SCHEMA).build()
                });
    }

    private static Stream<Object[]> automaticDeleteTombstonesOnRecognizedSourceTopics() {
        return Stream.of(
                automaticDeleteTombstone(DocumentState.POSTGRESQL_PROVIDER, "Document"),
                automaticDeleteTombstone(DocumentState.POSTGRESQL_PROVIDER, "DocumentCache"),
                automaticDeleteTombstone(DocumentState.SQLSERVER_PROVIDER, "Document"),
                automaticDeleteTombstone(DocumentState.SQLSERVER_PROVIDER, "DocumentCache"));
    }

    private static Object[] automaticDeleteTombstone(final String provider, final String sourceTable) {
        return new Object[] {provider, sourceTable};
    }

    private static Stream<String> cacheDropOperations() {
        return Stream.of("d", "t");
    }

    private static Object[] recordWithoutBefore(final String provider) {
        return new Object[] {provider, DocumentStateTestRecords.documentDeleteRecord(provider, null)};
    }

    private static Object[] recordWithNullBefore(final String provider, final Schema beforeSchema) {
        return new Object[] {
            provider, DocumentStateTestRecords.documentDeleteRecordWithBeforeSchema(provider, beforeSchema, null)
        };
    }

    private static Object[] recordWithMissingBeforeDocumentUuid(
            final String provider,
            final Schema beforeSchema) {
        return new Object[] {
            provider,
            DocumentStateTestRecords.documentDeleteRecordWithBeforeSchema(
                    provider, beforeSchema, new Struct(beforeSchema))
        };
    }

    private static Object[] recordWithNullBeforeDocumentUuid(
            final String provider,
            final Schema beforeSchema) {
        return new Object[] {
            provider,
            DocumentStateTestRecords.documentDeleteRecordWithBeforeSchema(
                    provider, beforeSchema, DocumentStateTestRecords.documentBeforeRow(beforeSchema, null))
        };
    }

    private static Object[] recordWithUnavailableBeforeDocumentUuid(
            final String provider,
            final Schema beforeSchema) {
        return new Object[] {
            provider,
            DocumentStateTestRecords.documentDeleteRecordWithBeforeSchema(
                    provider,
                    beforeSchema,
                    DocumentStateTestRecords.documentBeforeRow(beforeSchema, SQLSERVER_UNAVAILABLE_VALUE))
        };
    }

    private static SourceRecord recordWithKey(
            final SourceRecord record,
            final Schema keySchema,
            final Object key) {
        return new SourceRecord(
                record.sourcePartition(),
                record.sourceOffset(),
                record.topic(),
                record.kafkaPartition(),
                keySchema,
                key,
                record.valueSchema(),
                record.value());
    }

    private static SourceRecord recordWithTopic(final SourceRecord record, final String topic) {
        return new SourceRecord(
                record.sourcePartition(),
                record.sourceOffset(),
                topic,
                record.kafkaPartition(),
                record.keySchema(),
                record.key(),
                record.valueSchema(),
                record.value());
    }

    private static Schema requiredPostgresqlDocumentBeforeRowSchema() {
        return SchemaBuilder.struct()
                .name("server.dms." + DocumentStateTestRecords.POSTGRESQL_SOURCE_SCHEMA + ".Document.Value")
                .field(
                        DocumentStateTestRecords.DOCUMENT_UUID_FIELD,
                        DocumentStateTestRecords.pinnedUuidSchema(DocumentState.POSTGRESQL_PROVIDER))
                .build();
    }

    private static void assertFailure(
            final Throwable thrown,
            final DocumentState.FailureReason expectedReason,
            final String expectedSourceTable) {
        assertThat(thrown)
                .isInstanceOf(DataException.class)
                .isInstanceOf(DocumentState.TransformationFailureException.class);
        final DocumentState.TransformationFailureException exception =
                (DocumentState.TransformationFailureException) thrown;
        assertThat(exception.reason()).isEqualTo(expectedReason);
        assertThat(exception.metadata()).containsEntry("sourceTable", expectedSourceTable);
        assertThat(exception.metadata()).containsOnlyKeys(
                "provider", "sourceTopic", "sourceCategory", "sourceSchema", "sourceTable", "operation");
    }
}
