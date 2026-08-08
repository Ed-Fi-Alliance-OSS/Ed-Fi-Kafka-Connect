// SPDX-License-Identifier: Apache-2.0
// Licensed to the Ed-Fi Alliance under one or more agreements.
// The Ed-Fi Alliance licenses this file to you under the Apache License, Version 2.0.
// See the LICENSE and NOTICES files in the project root for more information.

package org.edfi.kafka.connect.transforms;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.errors.DataException;
import org.apache.kafka.connect.source.SourceRecord;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class DocumentStateUpsertTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String FIXTURE_CASE = "ordinary-link-bearing-student-school-association";

    @Test
    void Given_Postgresql_Shared_Fixture_Row_Should_Build_Public_Envelope_And_Strip_Metadata()
            throws IOException {
        final JsonNode cacheRow = readJson(sharedFixture(FIXTURE_CASE).resolve("expected-cache-row.json"));
        final JsonNode expectedPublic = readJson(
                sharedFixture(FIXTURE_CASE).resolve("expected-public-cdc-document.json"));
        final String documentUuid = cacheRow.get("documentUuid").asText();
        final Struct after = cacheRowFromFixture(cacheRow, DocumentState.POSTGRESQL_PROVIDER);
        final SourceRecord record = DocumentStateTestRecords.documentCacheRecordWithPublicMetadata(
                DocumentState.POSTGRESQL_PROVIDER, documentUuid.toUpperCase(Locale.ROOT), after);

        final SourceRecord result = DocumentStateTestRecords
                .configuredTransform(DocumentState.POSTGRESQL_PROVIDER)
                .apply(record);

        assertThat(result.topic()).isEqualTo(DocumentStateTestRecords.TARGET_TOPIC);
        assertThat(result.kafkaPartition()).isNull();
        assertThat(result.keySchema()).isSameAs(Schema.STRING_SCHEMA);
        assertThat(result.key()).isEqualTo(documentUuid);
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
        assertThat(toJson(value)).isEqualTo(toJson(expectedEnvelope(cacheRow, expectedPublic.get("document"))));
        assertThat(toJson(value).toString())
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

    private static Struct cacheRowFromFixture(final JsonNode cacheRow, final String provider) throws IOException {
        return DocumentStateTestRecords
                .cacheRowBuilder(provider)
                .field(DocumentStateTestRecords.DOCUMENT_UUID_FIELD,
                        DocumentStateTestRecords.pinnedUuidSchema(provider), cacheRow.get("documentUuid").asText())
                .field(DocumentStateTestRecords.PROJECT_NAME_FIELD, Schema.STRING_SCHEMA,
                        cacheRow.get("projectName").asText())
                .field(DocumentStateTestRecords.RESOURCE_NAME_FIELD, Schema.STRING_SCHEMA,
                        cacheRow.get("resourceName").asText())
                .field(DocumentStateTestRecords.RESOURCE_VERSION_FIELD, Schema.STRING_SCHEMA,
                        cacheRow.get("resourceVersion").asText())
                .field(DocumentStateTestRecords.CONTENT_VERSION_FIELD, Schema.INT64_SCHEMA,
                        cacheRow.get("contentVersion").asLong())
                .field(DocumentStateTestRecords.STREAM_ETAG_FIELD, Schema.STRING_SCHEMA,
                        cacheRow.get("streamEtag").asText())
                .field(DocumentStateTestRecords.LAST_MODIFIED_AT_FIELD,
                        DocumentStateTestRecords.lastModifiedAtSchema(provider),
                        cacheRow.get("lastModifiedAt").asText())
                .field(DocumentStateTestRecords.DOCUMENT_JSON_FIELD,
                        DocumentStateTestRecords.documentJsonSchema(provider),
                        MAPPER.writeValueAsString(cacheRow.get("documentJson")))
                .build();
    }

    private static Path sharedFixture(final String caseName) {
        final String fixtureRoot = System.getProperty("edfiDmsMaterializedDocumentFixtureRoot");
        assertThat(fixtureRoot)
                .as("Gradle property edfiDmsMaterializedDocumentFixtureRoot")
                .isNotBlank();
        final Path fixture = Path.of(fixtureRoot).resolve(caseName);
        assertThat(Files.isDirectory(fixture)).as("shared fixture case directory").isTrue();
        return fixture;
    }

    private static JsonNode readJson(final Path path) throws IOException {
        assertThat(Files.isRegularFile(path)).as("shared fixture file").isTrue();
        return MAPPER.readTree(path.toFile());
    }

    private static JsonNode expectedEnvelope(final JsonNode cacheRow, final JsonNode expectedDocument) {
        final ObjectNode expected = MAPPER.createObjectNode();
        expected.put("contractVersion", 1);
        expected.put("documentUuid", cacheRow.get("documentUuid").asText());
        expected.put("projectName", cacheRow.get("projectName").asText());
        expected.put("resourceName", cacheRow.get("resourceName").asText());
        expected.put("resourceVersion", cacheRow.get("resourceVersion").asText());
        expected.put("contentVersion", cacheRow.get("contentVersion").asLong());
        expected.put("lastModifiedAt", "2026-07-30T14:15:16Z");
        expected.set("document", expectedDocument);
        return expected;
    }

    private static JsonNode toJson(final Object value) throws IOException {
        return MAPPER.readTree(MAPPER.writeValueAsString(value));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> outputValue(final SourceRecord result) {
        return (Map<String, Object>) result.value();
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
