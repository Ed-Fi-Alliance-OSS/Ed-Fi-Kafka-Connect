// SPDX-License-Identifier: Apache-2.0
// Licensed to the Ed-Fi Alliance under one or more agreements.
// The Ed-Fi Alliance licenses this file to you under the Apache License, Version 2.0.
// See the LICENSE and NOTICES files in the project root for more information.

package org.edfi.kafka.connect.transforms;

import java.io.IOException;
import java.util.Map;
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

class DocumentStateUpsertTest {

    private static final String FIXTURE_CASE = "ordinary-link-bearing-student-school-association";

    @Test
    void Given_Postgresql_Shared_Fixture_Row_Should_Build_Public_Envelope_And_Strip_Metadata()
            throws IOException {
        final DocumentStateSharedFixtures.SharedFixture fixture =
                DocumentStateSharedFixtures.load(FIXTURE_CASE);
        final SourceRecord record = fixture.publicUpsertRecord(DocumentState.POSTGRESQL_PROVIDER);

        final SourceRecord result = DocumentStateTestRecords
                .configuredTransform(DocumentState.POSTGRESQL_PROVIDER)
                .apply(record);

        assertThat(result.topic()).isEqualTo(DocumentStateTestRecords.TARGET_TOPIC);
        assertThat(result.kafkaPartition()).isNull();
        assertThat(result.keySchema()).isSameAs(Schema.STRING_SCHEMA);
        assertThat(result.key()).isEqualTo(fixture.documentUuid());
        assertThat(result.valueSchema()).isNull();
        assertThat(result.value()).isInstanceOf(Map.class);
        assertThat(result.timestamp()).isNull();
        assertThat(result.headers()).isEmpty();
        assertThat(result.sourcePartition()).isEqualTo(record.sourcePartition());
        assertThat(result.sourceOffset()).isEqualTo(record.sourceOffset());

        final Map<String, Object> value = outputValue(result);
        assertThat(value.keySet()).containsExactly(
                "contractVersion",
                "documentUuid",
                "projectName",
                "resourceName",
                "resourceVersion",
                "contentVersion",
                "lastModifiedAt",
                "document");
        assertThat(DocumentStateSharedFixtures.toJson(value))
                .isEqualTo(DocumentStateSharedFixtures.toJson(fixture.expectedEnvelope()));
        assertThat(DocumentStateSharedFixtures.toJson(value).toString())
                .doesNotContain("DocumentId")
                .doesNotContain("ComputedAt")
                .doesNotContain("\"source\"")
                .doesNotContain("ts_ms");
    }

    @Test
    void Given_SqlServer_Pinned_Row_Should_Build_Public_Envelope() {
        final Struct after = DocumentStateTestRecords
                .cacheRowBuilder(DocumentState.SQLSERVER_PROVIDER)
                .field(
                        DocumentStateTestRecords.LAST_MODIFIED_AT_FIELD,
                        DocumentStateTestRecords.lastModifiedAtSchema(DocumentState.SQLSERVER_PROVIDER),
                        "2026-07-30T14:15:16.987654+00:00")
                .build();

        final SourceRecord result = DocumentStateTestRecords
                .configuredTransform(DocumentState.SQLSERVER_PROVIDER)
                .apply(DocumentStateTestRecords.documentCacheRecord(DocumentState.SQLSERVER_PROVIDER, after));

        final Map<String, Object> value = outputValue(result);
        assertThat(value).containsEntry("contractVersion", 1);
        assertThat(value).containsEntry("documentUuid", DocumentStateTestRecords.DOCUMENT_UUID);
        assertThat(value).containsEntry("contentVersion", 222L);
        assertThat(value).containsEntry("lastModifiedAt", "2026-07-30T14:15:16Z");
        assertThat(value.get("document")).isInstanceOf(Map.class);
        assertThat(value.get("documentUuid")).isEqualTo(result.key());

        final Map<String, Object> document = outputDocument(value);
        assertThat(document)
                .containsEntry("id", result.key())
                .containsEntry("_lastModifiedDate", value.get("lastModifiedAt"))
                .containsEntry("_etag", "222-01234567.j._.l.i");
    }

    @ParameterizedTest
    @MethodSource("malformedDocumentJsonRows")
    void Given_Malformed_DocumentJson_Should_Fail_With_Stable_Reason(
            final String provider,
            final String documentJson,
            final DocumentState.FailureReason expectedReason) {
        final Struct after = DocumentStateTestRecords
                .cacheRowBuilder(provider)
                .field(DocumentStateTestRecords.DOCUMENT_JSON_FIELD,
                        DocumentStateTestRecords.documentJsonSchema(provider), documentJson)
                .build();

        final Throwable thrown = catchThrowable(() -> DocumentStateTestRecords
                .configuredTransform(provider)
                .apply(DocumentStateTestRecords.documentCacheRecord(provider, after)));

        assertFailure(thrown, expectedReason);
    }

    @ParameterizedTest
    @MethodSource("malformedRequiredRows")
    void Given_Required_Row_Field_Is_Missing_Or_NonPinned_Should_Fail(
            final Struct after,
            final DocumentState.FailureReason expectedReason) {
        final Throwable thrown = catchThrowable(() -> DocumentStateTestRecords
                .configuredTransform(DocumentState.POSTGRESQL_PROVIDER)
                .apply(DocumentStateTestRecords.documentCacheRecord(DocumentState.POSTGRESQL_PROVIDER, after)));

        assertFailure(thrown, expectedReason);
    }

    private static Stream<Object[]> malformedDocumentJsonRows() {
        return Stream.of(
                malformedDocumentJson(DocumentState.POSTGRESQL_PROVIDER, "{",
                        DocumentState.FailureReason.INVALID_DOCUMENT_JSON),
                malformedDocumentJson(DocumentState.POSTGRESQL_PROVIDER, "null",
                        DocumentState.FailureReason.INVALID_DOCUMENT_JSON),
                malformedDocumentJson(DocumentState.POSTGRESQL_PROVIDER, "[]",
                        DocumentState.FailureReason.INVALID_DOCUMENT_JSON),
                malformedDocumentJson(DocumentState.POSTGRESQL_PROVIDER,
                        "{\"id\":\"" + DocumentStateTestRecords.DOCUMENT_UUID
                                + "\",\"_lastModifiedDate\":\"2026-07-30T14:15:16Z\",\"_etag\":\"old\"}",
                        DocumentState.FailureReason.DOCUMENT_JSON_HAS_ETAG),
                malformedDocumentJson(DocumentState.POSTGRESQL_PROVIDER,
                        "{\"id\":\"" + DocumentStateTestRecords.OTHER_DOCUMENT_UUID
                                + "\",\"_lastModifiedDate\":\"2026-07-30T14:15:16Z\"}",
                        DocumentState.FailureReason.PUBLIC_DOCUMENT_INVARIANT_MISMATCH),
                malformedDocumentJson(DocumentState.POSTGRESQL_PROVIDER,
                        "{\"id\":\"" + DocumentStateTestRecords.DOCUMENT_UUID
                                + "\",\"_lastModifiedDate\":\"2026-07-30T14:15:17Z\"}",
                        DocumentState.FailureReason.PUBLIC_DOCUMENT_INVARIANT_MISMATCH),
                malformedDocumentJson(DocumentState.SQLSERVER_PROVIDER, "__debezium_unavailable_value",
                        DocumentState.FailureReason.UNAVAILABLE_DOCUMENT_JSON));
    }

    private static Stream<Object[]> malformedRequiredRows() {
        return Stream.of(
                malformedRow(
                        DocumentStateTestRecords
                                .cacheRowBuilder(DocumentState.POSTGRESQL_PROVIDER)
                                .omit(DocumentStateTestRecords.PROJECT_NAME_FIELD)
                                .build(),
                        DocumentState.FailureReason.MISSING_REQUIRED_FIELD),
                malformedRow(
                        DocumentStateTestRecords
                                .cacheRowBuilder(DocumentState.POSTGRESQL_PROVIDER)
                                .field(DocumentStateTestRecords.PROJECT_NAME_FIELD,
                                        Schema.OPTIONAL_STRING_SCHEMA, "Ed-Fi")
                                .build(),
                        DocumentState.FailureReason.UNSUPPORTED_REQUIRED_FIELD_SHAPE),
                malformedRow(
                        DocumentStateTestRecords
                                .cacheRowBuilder(DocumentState.POSTGRESQL_PROVIDER)
                                .field(DocumentStateTestRecords.DOCUMENT_JSON_FIELD,
                                        Schema.STRING_SCHEMA,
                                        "{\"id\":\"" + DocumentStateTestRecords.DOCUMENT_UUID
                                                + "\",\"_lastModifiedDate\":\"2026-07-30T14:15:16Z\"}")
                                .build(),
                        DocumentState.FailureReason.UNSUPPORTED_REQUIRED_FIELD_SHAPE),
                malformedRow(
                        DocumentStateTestRecords
                                .cacheRowBuilder(DocumentState.POSTGRESQL_PROVIDER)
                                .omit(DocumentStateTestRecords.CONTENT_VERSION_FIELD)
                                .build(),
                        DocumentState.FailureReason.MISSING_REQUIRED_FIELD),
                malformedRow(
                        DocumentStateTestRecords
                                .cacheRowBuilder(DocumentState.POSTGRESQL_PROVIDER)
                                .field(DocumentStateTestRecords.CONTENT_VERSION_FIELD,
                                        Schema.STRING_SCHEMA, "222")
                                .build(),
                        DocumentState.FailureReason.UNSUPPORTED_REQUIRED_FIELD_SHAPE),
                malformedRow(
                        DocumentStateTestRecords
                                .cacheRowBuilder(DocumentState.POSTGRESQL_PROVIDER)
                                .field(DocumentStateTestRecords.CONTENT_VERSION_FIELD,
                                        SchemaBuilder.float64().build(), 222.5D)
                                .build(),
                        DocumentState.FailureReason.UNSUPPORTED_REQUIRED_FIELD_SHAPE));
    }

    private static Object[] malformedDocumentJson(
            final String provider,
            final String documentJson,
            final DocumentState.FailureReason expectedReason) {
        return new Object[] {provider, documentJson, expectedReason};
    }

    private static Object[] malformedRow(
            final Struct after,
            final DocumentState.FailureReason expectedReason) {
        return new Object[] {after, expectedReason};
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> outputValue(final SourceRecord result) {
        return (Map<String, Object>) result.value();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> outputDocument(final Map<String, Object> value) {
        return (Map<String, Object>) value.get("document");
    }

    private static void assertFailure(final Throwable thrown, final DocumentState.FailureReason expectedReason) {
        assertThat(thrown)
                .isInstanceOf(DataException.class)
                .isInstanceOf(DocumentState.TransformationFailureException.class);
        final DocumentState.TransformationFailureException exception =
                (DocumentState.TransformationFailureException) thrown;
        assertThat(exception.reason()).isEqualTo(expectedReason);
        assertThat(exception.metadata()).containsEntry("sourceTable", "DocumentCache");
        assertThat(exception.metadata()).containsOnlyKeys(
                "provider", "sourceTopic", "sourceCategory", "sourceSchema", "sourceTable", "operation");
    }
}
