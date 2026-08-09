// SPDX-License-Identifier: Apache-2.0
// Licensed to the Ed-Fi Alliance under one or more agreements.
// The Ed-Fi Alliance licenses this file to you under the Apache License, Version 2.0.
// See the LICENSE and NOTICES files in the project root for more information.

package org.edfi.kafka.connect.transforms;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Stream;

import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.errors.DataException;
import org.apache.kafka.connect.source.SourceRecord;

import com.fasterxml.jackson.databind.JsonNode;
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
        assertThat(result.valueSchema()).isNotNull();
        assertThat(result.valueSchema().type()).isEqualTo(Schema.Type.STRUCT);
        assertThat(result.value()).isInstanceOf(Struct.class);
        assertThat(result.timestamp()).isNull();
        assertThat(result.headers()).isEmpty();
        assertThat(result.sourcePartition()).isEqualTo(record.sourcePartition());
        assertThat(result.sourceOffset()).isEqualTo(record.sourceOffset());

        final Struct value = outputValue(result);
        assertThat(value.schema()).isSameAs(result.valueSchema());
        assertThat(value.schema().fields()).extracting(Field::name).containsExactly(
                "contractVersion",
                "documentUuid",
                "projectName",
                "resourceName",
                "resourceVersion",
                "contentVersion",
                "lastModifiedAt",
                "document");
        final JsonNode serializedValue = DocumentStateSharedFixtures.serializedPublicValue(result);
        assertThat(serializedValue.has("schema")).isFalse();
        assertThat(serializedValue.has("payload")).isFalse();
        assertThat(serializedValue)
                .isEqualTo(DocumentStateSharedFixtures.toJson(fixture.expectedEnvelope()));
        assertThat(serializedValue.toString())
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

        final Struct value = outputValue(result);
        assertThat(value.getInt32("contractVersion")).isEqualTo(1);
        assertThat(value.getString("documentUuid")).isEqualTo(DocumentStateTestRecords.DOCUMENT_UUID);
        assertThat(value.getInt64("contentVersion")).isEqualTo(222L);
        assertThat(value.getString("lastModifiedAt")).isEqualTo("2026-07-30T14:15:16Z");
        assertThat(value.getStruct("document")).isNotNull();
        assertThat(value.getString("documentUuid")).isEqualTo(result.key());

        final Struct document = outputDocument(value);
        assertThat(document)
                .returns(result.key(), it -> it.getString("id"))
                .returns(value.getString("lastModifiedAt"), it -> it.getString("_lastModifiedDate"))
                .returns("222-01234567.j._.l.i", it -> it.getString("_etag"));
    }

    @Test
    void Given_DocumentJson_Integral_Numbers_Should_Emit_Long_Values() {
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

        final Struct document = outputDocument(outputValue(result));
        assertThat(document.getInt64("schoolId")).isEqualTo(255901L);
        final Struct nested = document.getStruct("nested");
        assertThat(nested.getInt64("count")).isEqualTo(0L);
        final List<?> scores = document.getArray("scores");
        assertThat(scores).isEqualTo(List.of(1L, Long.MAX_VALUE));
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

        assertSchemaBackedPublicUpsert(result);
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
    void Given_DocumentJson_Mixed_Scale_Decimals_Should_Preserve_Exact_Number_Text() {
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

        final String serializedValue = DocumentStateSharedFixtures.serializedPublicValueText(result);
        assertThat(serializedValue)
                .contains("\"scores\":[1,1.20," + HIGH_PRECISION_DECIMAL.toPlainString() + "]")
                .contains("\"results\":[{\"score\":1},{\"score\":1.20},{\"score\":"
                        + HIGH_PRECISION_DECIMAL.toPlainString() + "}]");
    }

    @Test
    void Given_DocumentJson_Homogeneous_Object_Array_With_Null_Should_Preserve_Serialized_Shape() {
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

        assertThat(DocumentStateSharedFixtures.serializedPublicValueText(result))
                .contains("\"items\":[{\"a\":1,\"b\":\"first\"},null,{\"a\":2,\"b\":\"second\"}]");
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
                malformedDocumentJson(DocumentState.POSTGRESQL_PROVIDER,
                        "{\"id\":\"" + DocumentStateTestRecords.DOCUMENT_UUID
                                + "\",\"_lastModifiedDate\":\"2026-07-30T14:15:16Z\","
                                + "\"mixed\":[1,\"one\"]}",
                        DocumentState.FailureReason.INVALID_DOCUMENT_JSON),
                malformedDocumentJson(DocumentState.POSTGRESQL_PROVIDER,
                        "{\"id\":\"" + DocumentStateTestRecords.DOCUMENT_UUID
                                + "\",\"_lastModifiedDate\":\"2026-07-30T14:15:16Z\","
                                + "\"items\":[{\"a\":1},{\"b\":2}]}",
                        DocumentState.FailureReason.INVALID_DOCUMENT_JSON),
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

    private static Struct outputValue(final SourceRecord result) {
        assertSchemaBackedPublicUpsert(result);
        return (Struct) result.value();
    }

    private static Struct outputDocument(final Struct value) {
        return value.getStruct("document");
    }

    private static void assertSchemaBackedPublicUpsert(final SourceRecord result) {
        assertThat(result.valueSchema()).isNotNull();
        assertThat(result.valueSchema().type()).isEqualTo(Schema.Type.STRUCT);
        assertThat(result.value()).isInstanceOf(Struct.class);
    }

    private static void assertNumericDecimal(final JsonNode node, final BigDecimal expected) {
        assertThat(node.isNumber()).isTrue();
        assertThat(node.isTextual()).isFalse();
        assertThat(node.decimalValue().toPlainString()).isEqualTo(expected.toPlainString());
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
