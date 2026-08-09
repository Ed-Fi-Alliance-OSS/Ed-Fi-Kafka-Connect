// SPDX-License-Identifier: Apache-2.0
// Licensed to the Ed-Fi Alliance under one or more agreements.
// The Ed-Fi Alliance licenses this file to you under the Apache License, Version 2.0.
// See the LICENSE and NOTICES files in the project root for more information.

package org.edfi.kafka.connect.transforms;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.source.SourceRecord;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentStateSharedFixtureTest {

    private static final String ORDINARY_CASE = "ordinary-link-bearing-student-school-association";
    private static final String DESCRIPTOR_CASE = "descriptor-school-type";
    private static final String EXTENSION_CASE = "extension-student-school-association";

    @ParameterizedTest
    @MethodSource("representativeSharedFixtures")
    void Given_Shared_Dms_Fixture_Row_Should_Produce_Public_Envelope(
            final String caseName,
            final String provider,
            final String expectedDocumentField) throws IOException {
        final DocumentStateSharedFixtures.SharedFixture fixture = DocumentStateSharedFixtures.load(caseName);
        final SourceRecord result = DocumentStateTestRecords
                .configuredTransform(provider)
                .apply(fixture.publicUpsertRecord(provider));

        assertThat(result.topic()).isEqualTo(DocumentStateTestRecords.TARGET_TOPIC);
        assertThat(result.kafkaPartition()).isNull();
        assertThat(result.keySchema()).isSameAs(Schema.STRING_SCHEMA);
        assertThat(result.key()).isEqualTo(fixture.documentUuid());
        assertThat(result.valueSchema()).isNotNull();
        assertThat(result.valueSchema().type()).isEqualTo(Schema.Type.STRUCT);
        assertThat(result.value()).isInstanceOf(Struct.class);
        assertThat(result.timestamp()).isNull();
        assertThat(result.headers()).isEmpty();
        assertThat(DocumentStateSharedFixtures.serializedPublicValue(result))
                .isEqualTo(DocumentStateSharedFixtures.toJson(fixture.expectedEnvelope()));
        assertThat(fixture.expectedDocument().has(expectedDocumentField)).isTrue();
    }

    @ParameterizedTest
    @MethodSource("providers")
    void Given_Shared_Fixture_Record_Builder_Should_Use_Pinned_Provider_Schemas(final String provider)
            throws IOException {
        final DocumentStateSharedFixtures.SharedFixture fixture =
                DocumentStateSharedFixtures.load(ORDINARY_CASE);
        final SourceRecord record = fixture.publicUpsertRecord(provider);
        final Schema valueSchema = record.valueSchema();
        final Schema afterSchema = valueSchema.field("after").schema();

        assertField(record.keySchema(), DocumentStateTestRecords.DOCUMENT_UUID_FIELD, Schema.Type.STRING,
                uuidSchemaName(provider));
        assertField(valueSchema, "source", Schema.Type.STRUCT, sourceSchemaName(provider));
        assertField(valueSchema, "op", Schema.Type.STRING, null);
        assertField(afterSchema, DocumentStateTestRecords.DOCUMENT_UUID_FIELD, Schema.Type.STRING,
                uuidSchemaName(provider));
        assertField(afterSchema, DocumentStateTestRecords.DOCUMENT_JSON_FIELD, Schema.Type.STRING,
                documentJsonSchemaName(provider));
        assertField(afterSchema, DocumentStateTestRecords.LAST_MODIFIED_AT_FIELD, Schema.Type.STRING,
                lastModifiedAtSchemaName(provider));
        assertField(afterSchema, DocumentStateTestRecords.CONTENT_VERSION_FIELD, Schema.Type.INT64, null);
    }

    @Test
    void Given_Shared_Fixture_Root_Path_Does_Not_Exist_Should_Fail_Fast(@TempDir final Path temp) {
        final Path missingRoot = temp.resolve("missing");

        assertThatThrownBy(() -> DocumentStateSharedFixtures.load(missingRoot, ORDINARY_CASE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("shared DMS fixture root")
                .hasMessageContaining(missingRoot.toString());
    }

    @Test
    void Given_Shared_Fixture_File_Does_Not_Exist_Should_Fail_Fast(@TempDir final Path temp)
            throws IOException {
        final Path fixture = temp.resolve("case-with-missing-file");
        Files.createDirectories(fixture);
        Files.writeString(fixture.resolve("expected-cache-row.json"), "{}");

        assertThatThrownBy(() -> DocumentStateSharedFixtures.load(temp, "case-with-missing-file"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Required shared DMS fixture file is missing")
                .hasMessageContaining("expected-public-cdc-document.json");
    }

    private static Stream<Object[]> representativeSharedFixtures() {
        return Stream.of(
                representativeSharedFixture(ORDINARY_CASE, DocumentState.POSTGRESQL_PROVIDER, "schoolReference"),
                representativeSharedFixture(DESCRIPTOR_CASE, DocumentState.POSTGRESQL_PROVIDER, "namespace"),
                representativeSharedFixture(EXTENSION_CASE, DocumentState.POSTGRESQL_PROVIDER, "_ext"),
                representativeSharedFixture(ORDINARY_CASE, DocumentState.SQLSERVER_PROVIDER, "schoolReference"),
                representativeSharedFixture(DESCRIPTOR_CASE, DocumentState.SQLSERVER_PROVIDER, "namespace"),
                representativeSharedFixture(EXTENSION_CASE, DocumentState.SQLSERVER_PROVIDER, "_ext"));
    }

    private static Object[] representativeSharedFixture(
            final String caseName,
            final String provider,
            final String expectedDocumentField) {
        return new Object[] {caseName, provider, expectedDocumentField};
    }

    private static Stream<String> providers() {
        return Stream.of(DocumentState.POSTGRESQL_PROVIDER, DocumentState.SQLSERVER_PROVIDER);
    }

    private static void assertField(
            final Schema schema,
            final String fieldName,
            final Schema.Type schemaType,
            final String schemaName) {
        final Field field = schema.field(fieldName);
        assertThat(field).as(fieldName).isNotNull();
        assertThat(field.schema().type()).as(fieldName + " type").isEqualTo(schemaType);
        assertThat(field.schema().isOptional()).as(fieldName + " optional").isFalse();
        assertThat(field.schema().name()).as(fieldName + " schema name").isEqualTo(schemaName);
    }

    private static String sourceSchemaName(final String provider) {
        if (DocumentState.POSTGRESQL_PROVIDER.equals(provider)) {
            return DocumentStateTestRecords.POSTGRESQL_SOURCE_SCHEMA;
        }
        return DocumentStateTestRecords.SQLSERVER_SOURCE_SCHEMA;
    }

    private static String uuidSchemaName(final String provider) {
        if (DocumentState.POSTGRESQL_PROVIDER.equals(provider)) {
            return DocumentStateTestRecords.POSTGRESQL_UUID_SCHEMA;
        }
        return null;
    }

    private static String documentJsonSchemaName(final String provider) {
        if (DocumentState.POSTGRESQL_PROVIDER.equals(provider)) {
            return DocumentStateTestRecords.POSTGRESQL_JSON_SCHEMA;
        }
        return null;
    }

    private static String lastModifiedAtSchemaName(final String provider) {
        if (DocumentState.POSTGRESQL_PROVIDER.equals(provider)) {
            return DocumentStateTestRecords.POSTGRESQL_TIMESTAMP_SCHEMA;
        }
        return DocumentStateTestRecords.SQLSERVER_TIMESTAMP_SCHEMA;
    }
}
