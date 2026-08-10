// SPDX-License-Identifier: Apache-2.0
// Licensed to the Ed-Fi Alliance under one or more agreements.
// The Ed-Fi Alliance licenses this file to you under the Apache License, Version 2.0.
// See the LICENSE and NOTICES files in the project root for more information.

package org.edfi.kafka.connect.transforms;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.stream.Stream;

import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.errors.DataException;
import org.apache.kafka.connect.source.SourceRecord;

import com.fasterxml.jackson.databind.JsonNode;
import org.edfi.kafka.connect.converters.DocumentStateJsonConverter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class DocumentStateUpsertTest {

    private static final String FIXTURE_CASE = "ordinary-link-bearing-student-school-association";
    private static final BigDecimal HIGH_PRECISION_DECIMAL =
            new BigDecimal("3.141592653589793238462643383279");
    private static final BigDecimal OUT_OF_RANGE_INTEGER = new BigDecimal("9223372036854775808");

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
        assertLogicalBytePublicUpsert(result);
        assertThat(result.timestamp()).isNull();
        assertThat(result.headers()).isEmpty();
        assertThat(result.sourcePartition()).isEqualTo(record.sourcePartition());
        assertThat(result.sourceOffset()).isEqualTo(record.sourceOffset());

        final JsonNode serializedValue = DocumentStateSharedFixtures.serializedPublicValue(result);
        assertThat(serializedValue.has("schema")).isFalse();
        assertThat(serializedValue.has("payload")).isFalse();
        assertThat(fieldNames(serializedValue))
                .containsExactly(
                        "contractVersion",
                        "documentUuid",
                        "projectName",
                        "resourceName",
                        "resourceVersion",
                        "contentVersion",
                        "lastModifiedAt",
                        "document");
        assertThat(serializedValue)
                .isEqualTo(DocumentStateSharedFixtures.toJson(fixture.expectedEnvelope()));
        assertThat(serializedValue.toString())
                .doesNotContain("DocumentId")
                .doesNotContain("ComputedAt")
                .doesNotContain("\"source\"")
                .doesNotContain("ts_ms");
    }

    @Test
    void Given_SqlServer_Pinned_Row_Should_Build_Public_Envelope() throws IOException {
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

        final JsonNode value = outputValue(result);
        assertThat(value.get("contractVersion").intValue()).isEqualTo(1);
        assertThat(value.get("documentUuid").asText()).isEqualTo(DocumentStateTestRecords.DOCUMENT_UUID);
        assertThat(value.get("contentVersion").longValue()).isEqualTo(222L);
        assertThat(value.get("lastModifiedAt").asText()).isEqualTo("2026-07-30T14:15:16Z");
        assertThat(value.get("document").isObject()).isTrue();
        assertThat(value.get("documentUuid").asText()).isEqualTo(result.key());

        final JsonNode document = value.get("document");
        assertThat(document.get("id").asText()).isEqualTo(result.key());
        assertThat(document.get("_lastModifiedDate").asText())
                .isEqualTo(value.get("lastModifiedAt").asText());
        assertThat(document.get("_etag").asText()).isEqualTo("222-01234567.j._.l.i");
    }

    @Test
    void Given_DocumentJson_Integral_Numbers_Should_Emit_Long_Values() throws IOException {
        final Struct after = DocumentStateTestRecords
                .cacheRowBuilder(DocumentState.POSTGRESQL_PROVIDER)
                .field(
                        DocumentStateTestRecords.DOCUMENT_JSON_FIELD,
                        DocumentStateTestRecords.documentJsonSchema(DocumentState.POSTGRESQL_PROVIDER),
                        "{\"id\":\"" + DocumentStateTestRecords.DOCUMENT_UUID
                                + "\",\"_lastModifiedDate\":\"2026-07-30T14:15:16Z\","
                                + "\"schoolId\":255901,\"nested\":{\"count\":0},"
                                + "\"scores\":[1,9223372036854775807]}")
                .build();

        final SourceRecord result = DocumentStateTestRecords
                .configuredTransform(DocumentState.POSTGRESQL_PROVIDER)
                .apply(DocumentStateTestRecords.documentCacheRecord(DocumentState.POSTGRESQL_PROVIDER, after));

        final JsonNode document = outputValue(result).get("document");
        assertThat(document.get("schoolId").longValue()).isEqualTo(255901L);
        final JsonNode nested = document.get("nested");
        assertThat(nested.get("count").longValue()).isEqualTo(0L);
        final JsonNode scores = document.get("scores");
        assertThat(scores.get(0).longValue()).isEqualTo(1L);
        assertThat(scores.get(1).longValue()).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void Given_DocumentJson_Sample_Extension_Decimals_Should_Serialize_Without_Rounding()
            throws IOException {
        final Struct after = DocumentStateTestRecords
                .cacheRowBuilder(DocumentState.POSTGRESQL_PROVIDER)
                .field(
                        DocumentStateTestRecords.DOCUMENT_JSON_FIELD,
                        DocumentStateTestRecords.documentJsonSchema(DocumentState.POSTGRESQL_PROVIDER),
                        "{\"id\":\"" + DocumentStateTestRecords.DOCUMENT_UUID
                                + "\",\"_lastModifiedDate\":\"2026-07-30T14:15:16Z\","
                                + "\"_ext\":{\"sample\":{\"gpa\":"
                                + HIGH_PRECISION_DECIMAL.toPlainString() + ","
                                + "\"academicSummary\":{\"weightedGpa\":"
                                + HIGH_PRECISION_DECIMAL.toPlainString() + "},"
                                + "\"scoreHistory\":["
                                + HIGH_PRECISION_DECIMAL.toPlainString() + "]}},"
                                + "\"integerTooLarge\":" + OUT_OF_RANGE_INTEGER.toPlainString() + "}")
                .build();

        final SourceRecord result = DocumentStateTestRecords
                .configuredTransform(DocumentState.POSTGRESQL_PROVIDER)
                .apply(DocumentStateTestRecords.documentCacheRecord(DocumentState.POSTGRESQL_PROVIDER, after));

        assertLogicalBytePublicUpsert(result);
        final String serializedValue = DocumentStateSharedFixtures.serializedPublicValueText(result);
        assertThat(serializedValue)
                .doesNotContain("\"schema\"")
                .doesNotContain("\"payload\"")
                .contains("\"gpa\":" + HIGH_PRECISION_DECIMAL.toPlainString());

        final JsonNode root = DocumentStateSharedFixtures.serializedPublicValue(result);
        assertThat(root.has("schema")).isFalse();
        assertThat(root.has("payload")).isFalse();
        final JsonNode document = root.get("document");
        final JsonNode sampleExtension = document.get("_ext").get("sample");
        assertNumericDecimal(sampleExtension.get("gpa"), HIGH_PRECISION_DECIMAL);
        assertNumericDecimal(
                sampleExtension.get("academicSummary").get("weightedGpa"), HIGH_PRECISION_DECIMAL);
        assertNumericDecimal(sampleExtension.get("scoreHistory").get(0), HIGH_PRECISION_DECIMAL);
        assertNumericDecimal(document.get("integerTooLarge"), OUT_OF_RANGE_INTEGER);
    }

    @Test
    void Given_DocumentJson_Mixed_Scale_Decimals_Should_Preserve_Exact_Number_Values()
            throws IOException {
        final Struct after = DocumentStateTestRecords
                .cacheRowBuilder(DocumentState.POSTGRESQL_PROVIDER)
                .field(
                        DocumentStateTestRecords.DOCUMENT_JSON_FIELD,
                        DocumentStateTestRecords.documentJsonSchema(DocumentState.POSTGRESQL_PROVIDER),
                        "{\"id\":\"" + DocumentStateTestRecords.DOCUMENT_UUID
                                + "\",\"_lastModifiedDate\":\"2026-07-30T14:15:16Z\","
                                + "\"scores\":[1,1.20," + HIGH_PRECISION_DECIMAL.toPlainString() + "],"
                                + "\"results\":[{\"score\":1},{\"score\":1.20},{\"score\":"
                                + HIGH_PRECISION_DECIMAL.toPlainString() + "}]}")
                .build();

        final SourceRecord result = DocumentStateTestRecords
                .configuredTransform(DocumentState.POSTGRESQL_PROVIDER)
                .apply(DocumentStateTestRecords.documentCacheRecord(DocumentState.POSTGRESQL_PROVIDER, after));

        final JsonNode document = outputValue(result).get("document");
        final JsonNode scores = document.get("scores");
        assertNumericDecimal(scores.get(0), new BigDecimal("1"));
        assertNumericDecimal(scores.get(1), new BigDecimal("1.20"));
        assertNumericDecimal(scores.get(2), HIGH_PRECISION_DECIMAL);

        final JsonNode results = document.get("results");
        assertNumericDecimal(results.get(0).get("score"), new BigDecimal("1"));
        assertNumericDecimal(results.get(1).get("score"), new BigDecimal("1.20"));
        assertNumericDecimal(results.get(2).get("score"), HIGH_PRECISION_DECIMAL);
    }

    @Test
    void Given_DocumentJson_Object_Array_With_Null_Should_Preserve_Property_Presence()
            throws IOException {
        final Struct after = DocumentStateTestRecords
                .cacheRowBuilder(DocumentState.POSTGRESQL_PROVIDER)
                .field(
                        DocumentStateTestRecords.DOCUMENT_JSON_FIELD,
                        DocumentStateTestRecords.documentJsonSchema(DocumentState.POSTGRESQL_PROVIDER),
                        "{\"id\":\"" + DocumentStateTestRecords.DOCUMENT_UUID
                                + "\",\"_lastModifiedDate\":\"2026-07-30T14:15:16Z\","
                                + "\"items\":[{\"a\":1,\"b\":\"first\"},null,{\"b\":\"second\",\"a\":2}]}")
                .build();

        final SourceRecord result = DocumentStateTestRecords
                .configuredTransform(DocumentState.POSTGRESQL_PROVIDER)
                .apply(DocumentStateTestRecords.documentCacheRecord(DocumentState.POSTGRESQL_PROVIDER, after));

        final JsonNode items = outputValue(result).get("document").get("items");
        assertThat(items.get(0).has("a")).isTrue();
        assertThat(items.get(0).has("b")).isTrue();
        assertThat(items.get(1).isNull()).isTrue();
        assertThat(items.get(2).has("a")).isTrue();
        assertThat(items.get(2).has("b")).isTrue();
    }

    @Test
    void Given_DocumentJson_Object_Array_With_Different_Fields_Should_Preserve_Absent_Properties()
            throws IOException {
        final Struct after = DocumentStateTestRecords
                .cacheRowBuilder(DocumentState.POSTGRESQL_PROVIDER)
                .field(
                        DocumentStateTestRecords.DOCUMENT_JSON_FIELD,
                        DocumentStateTestRecords.documentJsonSchema(DocumentState.POSTGRESQL_PROVIDER),
                        "{\"id\":\"" + DocumentStateTestRecords.DOCUMENT_UUID
                                + "\",\"_lastModifiedDate\":\"2026-07-30T14:15:16Z\","
                                + "\"items\":[{\"a\":1},{\"b\":2}]}")
                .build();

        final SourceRecord result = DocumentStateTestRecords
                .configuredTransform(DocumentState.POSTGRESQL_PROVIDER)
                .apply(DocumentStateTestRecords.documentCacheRecord(DocumentState.POSTGRESQL_PROVIDER, after));

        final JsonNode items = outputValue(result).get("document").get("items");
        assertThat(items.get(0).has("a")).isTrue();
        assertThat(items.get(0).has("b")).isFalse();
        assertThat(items.get(1).has("a")).isFalse();
        assertThat(items.get(1).has("b")).isTrue();
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

    private static JsonNode outputValue(final SourceRecord result) throws IOException {
        assertLogicalBytePublicUpsert(result);
        return DocumentStateSharedFixtures.serializedPublicValue(result);
    }

    private static void assertLogicalBytePublicUpsert(final SourceRecord result) {
        assertThat(result.valueSchema()).isNotNull();
        assertThat(result.valueSchema().type()).isEqualTo(Schema.Type.BYTES);
        assertThat(result.valueSchema().name()).isEqualTo(DocumentStateJsonConverter.PUBLIC_SCHEMA_NAME);
        assertThat(result.valueSchema().version()).isEqualTo(DocumentStateJsonConverter.PUBLIC_SCHEMA_VERSION);
        assertThat(result.valueSchema().isOptional()).isFalse();
        assertThat(result.value()).isInstanceOf(byte[].class);
    }

    private static Iterable<String> fieldNames(final JsonNode node) {
        return node::fieldNames;
    }

    private static void assertNumericDecimal(final JsonNode node, final BigDecimal expected) {
        assertThat(node.isNumber()).isTrue();
        assertThat(node.isTextual()).isFalse();
        assertThat(node.decimalValue()).isEqualByComparingTo(expected);
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
