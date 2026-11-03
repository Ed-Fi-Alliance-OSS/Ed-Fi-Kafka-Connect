// SPDX-License-Identifier: Apache-2.0
// Licensed to the Ed-Fi Alliance under one or more agreements.
// The Ed-Fi Alliance licenses this file to you under the Apache License, Version 2.0.
// See the LICENSE and NOTICES files in the project root for more information.

package org.edfi.kafka.connect.transforms;

import java.io.IOException;
import java.util.stream.Stream;

import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.errors.DataException;
import org.apache.kafka.connect.source.SourceRecord;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/** Every case runs for both retained temporal fields: LastModifiedAt and ContentCreatedAt. */
class DocumentStateTimestampTest {

    private static final String LAST_MODIFIED_AT = DocumentStateTestRecords.LAST_MODIFIED_AT_FIELD;
    private static final String CONTENT_CREATED_AT = DocumentStateTestRecords.CONTENT_CREATED_AT_FIELD;
    private static final String VALID_TIMESTAMP = "2026-07-30T14:15:16Z";

    @ParameterizedTest
    @MethodSource("validTimestampRows")
    void Given_Pinned_Timestamp_Should_Truncate_Fractional_Seconds(
            final String sourceField,
            final String provider,
            final String sourceTimestamp,
            final String expectedTimestamp) throws IOException {
        final DocumentStateTestRecords.CacheRowBuilder builder = DocumentStateTestRecords
                .cacheRowBuilder(provider)
                .field(sourceField, DocumentStateTestRecords.timestampSchema(provider), sourceTimestamp);
        if (LAST_MODIFIED_AT.equals(sourceField)) {
            // The embedded _lastModifiedDate must agree with the truncated LastModifiedAt.
            builder.field(DocumentStateTestRecords.DOCUMENT_JSON_FIELD,
                    DocumentStateTestRecords.documentJsonSchema(provider),
                    documentJson(expectedTimestamp));
        }

        final SourceRecord result = DocumentStateTestRecords
                .configuredTransform(provider)
                .apply(DocumentStateTestRecords.documentCacheRecord(provider, builder.build()));

        assertThat(outputValue(result).get(publicField(sourceField)).asText()).isEqualTo(expectedTimestamp);
    }

    @ParameterizedTest
    @MethodSource("invalidTimestampRows")
    void Given_Unsupported_Or_NonUtc_Timestamp_Should_Fail_With_Stable_Reason(
            final String sourceField,
            final String provider,
            final Schema timestampSchema,
            final Object sourceTimestamp,
            final DocumentState.FailureReason expectedReason) {
        final Struct after = DocumentStateTestRecords
                .cacheRowBuilder(provider)
                .field(sourceField, timestampSchema, sourceTimestamp)
                .build();

        final Throwable thrown = catchThrowable(() -> DocumentStateTestRecords
                .configuredTransform(provider)
                .apply(DocumentStateTestRecords.documentCacheRecord(provider, after)));

        assertFailure(thrown, expectedReason);
    }

    @ParameterizedTest
    @MethodSource("providers")
    void Given_Distinct_Timestamps_Should_Publish_Each_From_Its_Own_Source_Field(final String provider)
            throws IOException {
        final Struct after = DocumentStateTestRecords
                .cacheRowBuilder(provider)
                .field(LAST_MODIFIED_AT, DocumentStateTestRecords.timestampSchema(provider),
                        "2026-07-30T14:15:16.5Z")
                .field(CONTENT_CREATED_AT, DocumentStateTestRecords.timestampSchema(provider),
                        "2024-01-02T03:04:05.5Z")
                .field(DocumentStateTestRecords.DOCUMENT_JSON_FIELD,
                        DocumentStateTestRecords.documentJsonSchema(provider),
                        documentJson(VALID_TIMESTAMP))
                .build();

        final JsonNode value = outputValue(DocumentStateTestRecords
                .configuredTransform(provider)
                .apply(DocumentStateTestRecords.documentCacheRecord(provider, after)));

        assertThat(value.get("lastModifiedAt").asText()).isEqualTo(VALID_TIMESTAMP);
        assertThat(value.get("documentCreatedAt").asText()).isEqualTo("2024-01-02T03:04:05Z");
        assertThat(value.get("document").get("_lastModifiedDate").asText()).isEqualTo(VALID_TIMESTAMP);
    }

    private static Stream<String> providers() {
        return Stream.of(DocumentState.POSTGRESQL_PROVIDER, DocumentState.SQLSERVER_PROVIDER);
    }

    private static Stream<Object[]> validTimestampRows() {
        return Stream.of(LAST_MODIFIED_AT, CONTENT_CREATED_AT).flatMap(field -> Stream.of(
                validTimestamp(field, DocumentState.POSTGRESQL_PROVIDER, "2026-07-30T14:15:16.999999Z",
                        "2026-07-30T14:15:16Z"),
                validTimestamp(field, DocumentState.SQLSERVER_PROVIDER, "2026-07-30T14:15:16.987654+00:00",
                        "2026-07-30T14:15:16Z"),
                // Just before the year rollover: rounding would cross into the next year.
                validTimestamp(field, DocumentState.POSTGRESQL_PROVIDER, "2026-12-31T23:59:59.999999Z",
                        "2026-12-31T23:59:59Z"),
                validTimestamp(field, DocumentState.SQLSERVER_PROVIDER, "2026-12-31T23:59:59.9999999Z",
                        "2026-12-31T23:59:59Z")));
    }

    private static Stream<Object[]> invalidTimestampRows() {
        return Stream.of(LAST_MODIFIED_AT, CONTENT_CREATED_AT).flatMap(field -> Stream.concat(
                providers().flatMap(provider -> unsupportedShapeRows(field, provider)),
                providers().flatMap(provider -> invalidValueRows(field, provider))));
    }

    /** Plain STRING, wrong logical name (with and without a version), v2, no version, optional, numeric. */
    private static Stream<Object[]> unsupportedShapeRows(final String field, final String provider) {
        final String pinnedName = pinnedTimestampSchemaName(provider);
        final String otherName = DocumentState.POSTGRESQL_PROVIDER.equals(provider)
                ? DocumentStateTestRecords.SQLSERVER_TIMESTAMP_SCHEMA
                : DocumentStateTestRecords.POSTGRESQL_TIMESTAMP_SCHEMA;
        return Stream.of(
                Schema.STRING_SCHEMA,
                SchemaBuilder.string().name(otherName).build(),
                SchemaBuilder.string().name(otherName).version(1).build(),
                SchemaBuilder.string().name(pinnedName).version(2).build(),
                SchemaBuilder.string().name(pinnedName).build(),
                SchemaBuilder.string().name(pinnedName).version(1).optional().build(),
                Schema.INT64_SCHEMA)
                .map(schema -> invalidTimestamp(field, provider, schema,
                        schema.type() == Schema.Type.INT64 ? (Object) 1L : VALID_TIMESTAMP,
                        DocumentState.FailureReason.UNSUPPORTED_REQUIRED_FIELD_SHAPE));
    }

    /** Malformed, non-UTC and offset-less values each fail with the field's own reason. */
    private static Stream<Object[]> invalidValueRows(final String field, final String provider) {
        final DocumentState.FailureReason reason = LAST_MODIFIED_AT.equals(field)
                ? DocumentState.FailureReason.INVALID_LAST_MODIFIED_AT
                : DocumentState.FailureReason.INVALID_CONTENT_CREATED_AT;
        return Stream.of("not-a-timestamp", "2026-07-30T09:15:16-05:00", "2026-07-30T14:15:16")
                .map(value -> invalidTimestamp(
                        field, provider, DocumentStateTestRecords.timestampSchema(provider), value, reason));
    }

    private static Object[] validTimestamp(
            final String field,
            final String provider,
            final String sourceTimestamp,
            final String expectedTimestamp) {
        return new Object[] {field, provider, sourceTimestamp, expectedTimestamp};
    }

    private static Object[] invalidTimestamp(
            final String field,
            final String provider,
            final Schema timestampSchema,
            final Object sourceTimestamp,
            final DocumentState.FailureReason expectedReason) {
        return new Object[] {field, provider, timestampSchema, sourceTimestamp, expectedReason};
    }

    private static String publicField(final String sourceField) {
        return LAST_MODIFIED_AT.equals(sourceField) ? "lastModifiedAt" : "documentCreatedAt";
    }

    private static String pinnedTimestampSchemaName(final String provider) {
        return DocumentState.POSTGRESQL_PROVIDER.equals(provider)
                ? DocumentStateTestRecords.POSTGRESQL_TIMESTAMP_SCHEMA
                : DocumentStateTestRecords.SQLSERVER_TIMESTAMP_SCHEMA;
    }

    private static String documentJson(final String lastModifiedAt) {
        return "{\"id\":\"" + DocumentStateTestRecords.DOCUMENT_UUID
                + "\",\"_lastModifiedDate\":\"" + lastModifiedAt + "\"}";
    }

    private static JsonNode outputValue(final SourceRecord result) throws IOException {
        return DocumentStateSharedFixtures.serializedPublicValue(result);
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
