// SPDX-License-Identifier: Apache-2.0
// Licensed to the Ed-Fi Alliance under one or more agreements.
// The Ed-Fi Alliance licenses this file to you under the Apache License, Version 2.0.
// See the LICENSE and NOTICES files in the project root for more information.

package org.edfi.kafka.connect.transforms;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
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
import static org.assertj.core.api.Assertions.entry;

class DocumentStateKeyTest {

    private static final String POSTGRESQL_SOURCE_SCHEMA = "io.debezium.connector.postgresql.Source";
    private static final String SQLSERVER_SOURCE_SCHEMA = "io.debezium.connector.sqlserver.Source";
    private static final String POSTGRESQL_UUID_SCHEMA = "io.debezium.data.Uuid";
    private static final String DOCUMENT_UUID = "f81d4fae-7dec-11d0-a765-00a0c91e6bf6";
    private static final String UPPER_DOCUMENT_UUID = "F81D4FAE-7DEC-11D0-A765-00A0C91E6BF6";
    private static final String OTHER_DOCUMENT_UUID = "00000000-0000-4000-8000-000000000001";

    @ParameterizedTest
    @MethodSource("validPublicDocumentKeys")
    void Given_Public_Document_Record_Should_Normalize_Key_To_Lowercase_String(
            final String provider,
            final SourceRecord record) {
        final DocumentState<SourceRecord> transform = configuredTransform(provider);

        final DocumentState.ValidatedDocumentKey documentKey =
                transform.validatePublicDocumentKey(record, transform.classify(record));

        assertThat(documentKey.schema()).isSameAs(Schema.STRING_SCHEMA);
        assertThat(documentKey.value()).isEqualTo(DOCUMENT_UUID);
    }

    @ParameterizedTest
    @MethodSource("invalidPublicDocumentKeys")
    void Given_Malformed_Public_Document_Key_Should_Fail_Before_Output_Transformation(
            final String provider,
            final SourceRecord record,
            final DocumentState.FailureReason expectedReason) {
        final Throwable thrown = catchThrowable(() -> configuredTransform(provider).apply(record));

        assertFailure(thrown, expectedReason);
    }

    @Test
    void Given_DocumentCache_Row_DocumentUuid_Differs_From_Key_Should_Fail() {
        final SourceRecord record = documentCacheRecord(
                DocumentState.POSTGRESQL_PROVIDER,
                keyStructSchema(DocumentState.POSTGRESQL_PROVIDER, pinnedUuidSchema(DocumentState.POSTGRESQL_PROVIDER)),
                keyStruct(DocumentState.POSTGRESQL_PROVIDER, DOCUMENT_UUID),
                rowStructSchema(DocumentState.POSTGRESQL_PROVIDER, pinnedUuidSchema(DocumentState.POSTGRESQL_PROVIDER)),
                rowStruct(DocumentState.POSTGRESQL_PROVIDER, OTHER_DOCUMENT_UUID));

        final Throwable thrown = catchThrowable(() -> configuredTransform(DocumentState.POSTGRESQL_PROVIDER)
                .apply(record));

        assertFailure(thrown, DocumentState.FailureReason.DOCUMENT_UUID_MISMATCH);
        final DocumentState.TransformationFailureException exception =
                (DocumentState.TransformationFailureException) thrown;
        assertThat(exception.metadata()).contains(
                entry("sourceTable", "DocumentCache"),
                entry("operation", "c"));
    }

    @ParameterizedTest
    @MethodSource("unsupportedCacheRowDocumentUuids")
    void Given_DocumentCache_Row_DocumentUuid_Uses_NonPinned_Shape_Should_Fail(
            final String provider,
            final Schema rowUuidSchema) {
        final SourceRecord record = documentCacheRecord(
                provider,
                keyStructSchema(provider, pinnedUuidSchema(provider)),
                keyStruct(provider, DOCUMENT_UUID),
                rowStructSchema(provider, rowUuidSchema),
                rowStruct(rowStructSchema(provider, rowUuidSchema), DOCUMENT_UUID));

        final Throwable thrown = catchThrowable(() -> configuredTransform(provider).apply(record));

        assertFailure(thrown, DocumentState.FailureReason.UNSUPPORTED_DOCUMENT_UUID_SHAPE);
    }

    @Test
    void Given_DocumentCache_After_Row_Field_Is_Required_Should_Fail() {
        final Schema requiredRowSchema = requiredRowStructSchema(
                DocumentState.POSTGRESQL_PROVIDER, pinnedUuidSchema(DocumentState.POSTGRESQL_PROVIDER));
        final SourceRecord record = documentCacheRecord(
                DocumentState.POSTGRESQL_PROVIDER,
                keyStructSchema(DocumentState.POSTGRESQL_PROVIDER, pinnedUuidSchema(DocumentState.POSTGRESQL_PROVIDER)),
                keyStruct(DocumentState.POSTGRESQL_PROVIDER, DOCUMENT_UUID),
                requiredRowSchema,
                rowStruct(requiredRowSchema, DOCUMENT_UUID));

        final Throwable thrown = catchThrowable(() -> configuredTransform(DocumentState.POSTGRESQL_PROVIDER)
                .apply(record));

        assertFailure(thrown, DocumentState.FailureReason.UNSUPPORTED_RETAINED_ROW_SHAPE);
    }

    @Test
    void Given_Recognized_Drop_With_Malformed_Key_Should_Not_Validate_Key() {
        final SourceRecord record = record(
                DocumentState.POSTGRESQL_PROVIDER,
                "DocumentCache",
                "d",
                Schema.STRING_SCHEMA,
                "not-a-schema-backed-key",
                null,
                null);

        assertThat(configuredTransform(DocumentState.POSTGRESQL_PROVIDER).apply(record)).isNull();
    }

    private static Stream<Object[]> validPublicDocumentKeys() {
        return Stream.of(
                new Object[] {
                    DocumentState.POSTGRESQL_PROVIDER,
                    documentCacheRecord(
                            DocumentState.POSTGRESQL_PROVIDER,
                            keyStruct(DocumentState.POSTGRESQL_PROVIDER, UPPER_DOCUMENT_UUID),
                            rowStruct(DocumentState.POSTGRESQL_PROVIDER, DOCUMENT_UUID))
                },
                new Object[] {
                    DocumentState.SQLSERVER_PROVIDER,
                    documentRecord(
                            DocumentState.SQLSERVER_PROVIDER,
                            keyStruct(DocumentState.SQLSERVER_PROVIDER, UPPER_DOCUMENT_UUID))
                });
    }

    private static Stream<Object[]> invalidPublicDocumentKeys() {
        return Stream.of(
                invalidKey(DocumentState.POSTGRESQL_PROVIDER, null, null,
                        DocumentState.FailureReason.MISSING_DOCUMENT_KEY),
                invalidKey(DocumentState.POSTGRESQL_PROVIDER, null, Map.of("DocumentUuid", DOCUMENT_UUID),
                        DocumentState.FailureReason.UNSUPPORTED_DOCUMENT_KEY_SHAPE),
                invalidKey(DocumentState.POSTGRESQL_PROVIDER, Schema.STRING_SCHEMA, DOCUMENT_UUID,
                        DocumentState.FailureReason.UNSUPPORTED_DOCUMENT_KEY_SHAPE),
                invalidKey(DocumentState.POSTGRESQL_PROVIDER, null, UUID.fromString(DOCUMENT_UUID),
                        DocumentState.FailureReason.UNSUPPORTED_DOCUMENT_KEY_SHAPE),
                invalidKey(
                        DocumentState.POSTGRESQL_PROVIDER,
                        keyStructSchema(DocumentState.POSTGRESQL_PROVIDER, Schema.BYTES_SCHEMA),
                        keyStruct(keyStructSchema(DocumentState.POSTGRESQL_PROVIDER, Schema.BYTES_SCHEMA),
                                new byte[] {1, 2, 3}),
                        DocumentState.FailureReason.UNSUPPORTED_DOCUMENT_KEY_SHAPE),
                invalidKey(
                        DocumentState.POSTGRESQL_PROVIDER,
                        SchemaBuilder.struct().name("server.dms.Document.Key").build(),
                        new Struct(SchemaBuilder.struct().name("server.dms.Document.Key").build()),
                        DocumentState.FailureReason.MISSING_DOCUMENT_UUID),
                invalidKey(
                        DocumentState.POSTGRESQL_PROVIDER,
                        keyStructSchema(DocumentState.POSTGRESQL_PROVIDER,
                                pinnedUuidSchema(DocumentState.POSTGRESQL_PROVIDER)),
                        keyStruct(DocumentState.POSTGRESQL_PROVIDER, "not-a-uuid"),
                        DocumentState.FailureReason.INVALID_DOCUMENT_UUID),
                invalidKey(
                        DocumentState.POSTGRESQL_PROVIDER,
                        keyStructSchema(DocumentState.POSTGRESQL_PROVIDER,
                                postgresqlUuidSchemaWithVersion(2)),
                        keyStruct(
                                keyStructSchema(DocumentState.POSTGRESQL_PROVIDER,
                                        postgresqlUuidSchemaWithVersion(2)),
                                DOCUMENT_UUID),
                        DocumentState.FailureReason.UNSUPPORTED_DOCUMENT_KEY_SHAPE),
                invalidKey(
                        DocumentState.POSTGRESQL_PROVIDER,
                        keyStructSchema(DocumentState.POSTGRESQL_PROVIDER, Schema.STRING_SCHEMA),
                        keyStruct(keyStructSchema(DocumentState.POSTGRESQL_PROVIDER, Schema.STRING_SCHEMA),
                                DOCUMENT_UUID),
                        DocumentState.FailureReason.UNSUPPORTED_DOCUMENT_KEY_SHAPE),
                invalidKey(
                        DocumentState.POSTGRESQL_PROVIDER,
                        keyStructSchemaWithExtraField(DocumentState.POSTGRESQL_PROVIDER),
                        keyStruct(
                                keyStructSchemaWithExtraField(DocumentState.POSTGRESQL_PROVIDER),
                                DOCUMENT_UUID),
                        DocumentState.FailureReason.UNSUPPORTED_DOCUMENT_KEY_SHAPE),
                invalidKey(
                        DocumentState.SQLSERVER_PROVIDER,
                        keyStructSchema(DocumentState.SQLSERVER_PROVIDER,
                                SchemaBuilder.string().name(POSTGRESQL_UUID_SCHEMA).build()),
                        keyStruct(
                                keyStructSchema(DocumentState.SQLSERVER_PROVIDER,
                                        SchemaBuilder.string().name(POSTGRESQL_UUID_SCHEMA).build()),
                                DOCUMENT_UUID),
                        DocumentState.FailureReason.UNSUPPORTED_DOCUMENT_KEY_SHAPE),
                invalidKey(
                        DocumentState.POSTGRESQL_PROVIDER,
                        keyStructSchema(DocumentState.POSTGRESQL_PROVIDER,
                                SchemaBuilder.string().name(POSTGRESQL_UUID_SCHEMA).optional().build()),
                        keyStruct(
                                keyStructSchema(DocumentState.POSTGRESQL_PROVIDER,
                                        SchemaBuilder.string().name(POSTGRESQL_UUID_SCHEMA).optional().build()),
                                DOCUMENT_UUID),
                        DocumentState.FailureReason.UNSUPPORTED_DOCUMENT_KEY_SHAPE));
    }

    private static Stream<Object[]> unsupportedCacheRowDocumentUuids() {
        return Stream.of(
                new Object[] {DocumentState.POSTGRESQL_PROVIDER, Schema.STRING_SCHEMA},
                new Object[] {DocumentState.POSTGRESQL_PROVIDER, postgresqlUuidSchemaWithVersion(2)},
                new Object[] {
                    DocumentState.SQLSERVER_PROVIDER, SchemaBuilder.string().name(POSTGRESQL_UUID_SCHEMA).build()
                });
    }

    private static Object[] invalidKey(
            final String provider,
            final Schema keySchema,
            final Object key,
            final DocumentState.FailureReason expectedReason) {
        return new Object[] {provider, documentRecord(provider, keySchema, key), expectedReason};
    }

    private static DocumentState<SourceRecord> configuredTransform(final String provider) {
        final DocumentState<SourceRecord> transform = new DocumentState<>();
        final Map<String, Object> config = new HashMap<>();
        config.put(DocumentState.PROVIDER_CONFIG, provider);
        config.put(DocumentState.TARGET_TOPIC_CONFIG, "edfi.documents");
        config.put(DocumentState.PROGRESS_TOPIC_CONFIG, "edfi.documents.cdc-progress");
        transform.configure(config);
        return transform;
    }

    private static SourceRecord documentRecord(final String provider, final Object key) {
        return documentRecord(provider, keyStructSchema(provider, pinnedUuidSchema(provider)), key);
    }

    private static SourceRecord documentRecord(final String provider, final Schema keySchema, final Object key) {
        return record(provider, "Document", "d", keySchema, key, null, null);
    }

    private static SourceRecord documentCacheRecord(
            final String provider,
            final Object key,
            final Struct after) {
        return documentCacheRecord(
                provider,
                keyStructSchema(provider, pinnedUuidSchema(provider)),
                key,
                after.schema(),
                after);
    }

    private static SourceRecord documentCacheRecord(
            final String provider,
            final Schema keySchema,
            final Object key,
            final Schema afterSchema,
            final Struct after) {
        return record(provider, "DocumentCache", "c", keySchema, key, afterSchema, after);
    }

    private static SourceRecord record(
            final String provider,
            final String sourceTable,
            final String operation,
            final Schema keySchema,
            final Object key,
            final Schema afterSchema,
            final Struct after) {
        final Schema sourceStructSchema = sourceSchema(sourceSchemaName(provider));
        final SchemaBuilder valueSchemaBuilder = SchemaBuilder.struct()
                .field("source", sourceStructSchema)
                .field("op", Schema.STRING_SCHEMA);
        if (afterSchema != null) {
            valueSchemaBuilder.field("after", afterSchema);
        }

        final Schema valueSchema = valueSchemaBuilder.build();
        final Struct value = new Struct(valueSchema)
                .put("source", source(sourceStructSchema, "dms", sourceTable))
                .put("op", operation);
        if (after != null) {
            value.put("after", after);
        }
        return new SourceRecord(
                sourcePartition(), sourceOffset(), "server.dms." + sourceTable, null,
                keySchema, key, valueSchema, value);
    }

    private static Struct keyStruct(final String provider, final Object documentUuid) {
        return keyStruct(keyStructSchema(provider, pinnedUuidSchema(provider)), documentUuid);
    }

    private static Struct keyStruct(final Schema keySchema, final Object documentUuid) {
        final Struct key = new Struct(keySchema);
        if (keySchema.field("DocumentUuid") != null && documentUuid != null) {
            key.put("DocumentUuid", documentUuid);
        }
        return key;
    }

    private static Schema keyStructSchema(final String provider, final Schema documentUuidSchema) {
        return SchemaBuilder.struct()
                .name("server.dms." + sourceSchemaName(provider) + ".Key")
                .field("DocumentUuid", documentUuidSchema)
                .build();
    }

    private static Schema keyStructSchemaWithExtraField(final String provider) {
        return SchemaBuilder.struct()
                .name("server.dms." + sourceSchemaName(provider) + ".Key")
                .field("DocumentUuid", pinnedUuidSchema(provider))
                .field("Unexpected", Schema.OPTIONAL_STRING_SCHEMA)
                .build();
    }

    private static Struct rowStruct(final String provider, final Object documentUuid) {
        return rowStruct(rowStructSchema(provider, pinnedUuidSchema(provider)), documentUuid);
    }

    private static Struct rowStruct(final Schema rowSchema, final Object documentUuid) {
        final Struct row = new Struct(rowSchema);
        if (rowSchema.field("DocumentUuid") != null && documentUuid != null) {
            row.put("DocumentUuid", documentUuid);
        }
        return row;
    }

    private static Schema rowStructSchema(final String provider, final Schema documentUuidSchema) {
        return SchemaBuilder.struct()
                .name("server.dms." + sourceSchemaName(provider) + ".DocumentCache.Value")
                .optional()
                .field("DocumentUuid", documentUuidSchema)
                .build();
    }

    private static Schema requiredRowStructSchema(final String provider, final Schema documentUuidSchema) {
        return SchemaBuilder.struct()
                .name("server.dms." + sourceSchemaName(provider) + ".DocumentCache.Value")
                .field("DocumentUuid", documentUuidSchema)
                .build();
    }

    private static Schema pinnedUuidSchema(final String provider) {
        if (DocumentState.POSTGRESQL_PROVIDER.equals(provider)) {
            return postgresqlUuidSchemaWithVersion(DocumentStateTestRecords.DEBEZIUM_LOGICAL_SCHEMA_VERSION);
        }
        return Schema.STRING_SCHEMA;
    }

    private static Schema postgresqlUuidSchemaWithVersion(final int version) {
        return SchemaBuilder.string().name(POSTGRESQL_UUID_SCHEMA).version(version).build();
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

    private static String sourceSchemaName(final String provider) {
        if (DocumentState.POSTGRESQL_PROVIDER.equals(provider)) {
            return POSTGRESQL_SOURCE_SCHEMA;
        }
        return SQLSERVER_SOURCE_SCHEMA;
    }

    private static Map<String, String> sourcePartition() {
        return Map.of("server", "dms");
    }

    private static Map<String, Long> sourceOffset() {
        return Map.of("position", 1L);
    }

    private static void assertFailure(final Throwable thrown, final DocumentState.FailureReason expectedReason) {
        assertThat(thrown)
                .isInstanceOf(DataException.class)
                .isInstanceOf(DocumentState.TransformationFailureException.class);
        final DocumentState.TransformationFailureException exception =
                (DocumentState.TransformationFailureException) thrown;
        assertThat(exception.reason()).isEqualTo(expectedReason);
    }
}
