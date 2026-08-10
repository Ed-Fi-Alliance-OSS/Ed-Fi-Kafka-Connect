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

class DocumentStateTimestampTest {

    @ParameterizedTest
    @MethodSource("validTimestampRows")
    void Given_Pinned_Timestamp_Should_Truncate_Fractional_Seconds(
            final String provider,
            final String sourceTimestamp,
            final String expectedTimestamp) throws IOException {
        final Struct after = DocumentStateTestRecords
                .cacheRowBuilder(provider)
                .field(DocumentStateTestRecords.LAST_MODIFIED_AT_FIELD,
                        DocumentStateTestRecords.lastModifiedAtSchema(provider), sourceTimestamp)
                .field(DocumentStateTestRecords.DOCUMENT_JSON_FIELD,
                        DocumentStateTestRecords.documentJsonSchema(provider),
                        documentJson(expectedTimestamp))
                .build();

        final SourceRecord result = DocumentStateTestRecords
                .configuredTransform(provider)
                .apply(DocumentStateTestRecords.documentCacheRecord(provider, after));

        assertThat(outputValue(result).get("lastModifiedAt").asText()).isEqualTo(expectedTimestamp);
    }

    @ParameterizedTest
    @MethodSource("invalidTimestampRows")
    void Given_Unsupported_Or_NonUtc_Timestamp_Should_Fail_With_Stable_Reason(
            final String provider,
            final Schema timestampSchema,
            final Object sourceTimestamp,
            final DocumentState.FailureReason expectedReason) {
        final Struct after = DocumentStateTestRecords
                .cacheRowBuilder(provider)
                .field(DocumentStateTestRecords.LAST_MODIFIED_AT_FIELD, timestampSchema, sourceTimestamp)
                .build();

        final Throwable thrown = catchThrowable(() -> DocumentStateTestRecords
                .configuredTransform(provider)
                .apply(DocumentStateTestRecords.documentCacheRecord(provider, after)));

        assertFailure(thrown, expectedReason);
    }

    private static Stream<Object[]> validTimestampRows() {
        return Stream.of(
                validTimestamp(DocumentState.POSTGRESQL_PROVIDER, "2026-07-30T14:15:16.999999Z",
                        "2026-07-30T14:15:16Z"),
                validTimestamp(DocumentState.SQLSERVER_PROVIDER, "2026-07-30T14:15:16.987654+00:00",
                        "2026-07-30T14:15:16Z"));
    }

    private static Stream<Object[]> invalidTimestampRows() {
        return Stream.of(
                invalidTimestamp(
                        DocumentState.POSTGRESQL_PROVIDER,
                        Schema.STRING_SCHEMA,
                        "2026-07-30T14:15:16Z",
                        DocumentState.FailureReason.UNSUPPORTED_REQUIRED_FIELD_SHAPE),
                invalidTimestamp(
                        DocumentState.POSTGRESQL_PROVIDER,
                        SchemaBuilder.string().name(DocumentStateTestRecords.SQLSERVER_TIMESTAMP_SCHEMA).build(),
                        "2026-07-30T14:15:16Z",
                        DocumentState.FailureReason.UNSUPPORTED_REQUIRED_FIELD_SHAPE),
                invalidTimestamp(
                        DocumentState.SQLSERVER_PROVIDER,
                        Schema.INT64_SCHEMA,
                        1L,
                        DocumentState.FailureReason.UNSUPPORTED_REQUIRED_FIELD_SHAPE),
                invalidTimestamp(
                        DocumentState.POSTGRESQL_PROVIDER,
                        DocumentStateTestRecords.lastModifiedAtSchema(DocumentState.POSTGRESQL_PROVIDER),
                        "not-a-timestamp",
                        DocumentState.FailureReason.INVALID_LAST_MODIFIED_AT),
                invalidTimestamp(
                        DocumentState.SQLSERVER_PROVIDER,
                        DocumentStateTestRecords.lastModifiedAtSchema(DocumentState.SQLSERVER_PROVIDER),
                        "2026-07-30T09:15:16-05:00",
                        DocumentState.FailureReason.INVALID_LAST_MODIFIED_AT),
                invalidTimestamp(
                        DocumentState.POSTGRESQL_PROVIDER,
                        DocumentStateTestRecords.lastModifiedAtSchema(DocumentState.POSTGRESQL_PROVIDER),
                        "2026-07-30T14:15:16",
                        DocumentState.FailureReason.INVALID_LAST_MODIFIED_AT));
    }

    private static Object[] validTimestamp(
            final String provider,
            final String sourceTimestamp,
            final String expectedTimestamp) {
        return new Object[] {provider, sourceTimestamp, expectedTimestamp};
    }

    private static Object[] invalidTimestamp(
            final String provider,
            final Schema timestampSchema,
            final Object sourceTimestamp,
            final DocumentState.FailureReason expectedReason) {
        return new Object[] {provider, timestampSchema, sourceTimestamp, expectedReason};
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
