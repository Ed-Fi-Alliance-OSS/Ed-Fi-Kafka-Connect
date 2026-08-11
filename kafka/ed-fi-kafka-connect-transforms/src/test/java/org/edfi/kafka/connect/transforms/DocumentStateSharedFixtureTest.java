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
import org.apache.kafka.connect.source.SourceRecord;

import com.fasterxml.jackson.databind.JsonNode;
import org.edfi.kafka.connect.converters.DocumentStateJsonConverter;
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
    private static final String PROPERTY_ABSENCE_CASE = "school-address-property-absence";
    private static final String FIXTURE_MANIFEST_FILE = "fixture.json";
    private static final String FIXTURE_VERSION = "materialized-document-fixture-v1";
    private static final String CACHE_ROW_FILE = "expected-cache-row.json";
    private static final String PUBLIC_DOCUMENT_FILE = "expected-public-cdc-document.json";

    @TempDir
    private Path temp;

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
        assertPublicJsonBytes(result);
        assertThat(result.timestamp()).isNull();
        assertThat(result.headers()).isEmpty();
        assertThat(DocumentStateSharedFixtures.serializedPublicValue(result))
                .isEqualTo(DocumentStateSharedFixtures.toJson(fixture.expectedEnvelope()));
        assertThat(fixture.expectedDocument().has(expectedDocumentField)).isTrue();
    }

    @ParameterizedTest
    @MethodSource("providers")
    void Given_Shared_Collection_Fixture_Should_Preserve_Property_Absence_Through_Converter(
            final String provider) throws IOException {
        final DocumentStateSharedFixtures.SharedFixture fixture =
                DocumentStateSharedFixtures.load(PROPERTY_ABSENCE_CASE);
        final SourceRecord result = DocumentStateTestRecords
                .configuredTransform(provider)
                .apply(fixture.publicUpsertRecord(provider));

        final JsonNode serializedValue = DocumentStateSharedFixtures.serializedPublicValue(result);

        assertPublicJsonBytes(result);
        assertThat(serializedValue).isEqualTo(DocumentStateSharedFixtures.toJson(fixture.expectedEnvelope()));
        final JsonNode addresses = serializedValue.get("document").get("addresses");
        assertThat(addresses.get(0).has("addressTypeDescriptor")).isTrue();
        assertThat(addresses.get(0).get("addressTypeDescriptor").isNull()).isFalse();
        assertThat(addresses.get(1).has("addressTypeDescriptor")).isFalse();
        assertThat(addresses.get(1).has("city")).isTrue();
        assertNoExplicitNull(serializedValue, "$");
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
    void Given_Shared_Fixture_Root_Path_Does_Not_Exist_Should_Fail_Fast() {
        final Path missingRoot = temp.resolve("missing");

        assertThatThrownBy(() -> DocumentStateSharedFixtures.load(missingRoot, ORDINARY_CASE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("shared DMS fixture root")
                .hasMessageContaining(missingRoot.toString());
    }

    @Test
    void Given_Shared_Fixture_Manifest_Does_Not_Exist_Should_Fail_Fast() throws IOException {
        createFixtureDirectory("case-with-missing-manifest");

        assertThatThrownBy(() -> DocumentStateSharedFixtures.load(temp, "case-with-missing-manifest"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Required shared DMS fixture file is missing")
                .hasMessageContaining(FIXTURE_MANIFEST_FILE);
    }

    @Test
    void Given_Shared_Fixture_Manifest_Case_Name_Does_Not_Match_Should_Fail_Fast()
            throws IOException {
        final Path fixture = createFixtureDirectory("requested-case");
        writeManifest(fixture, "other-case");

        assertThatThrownBy(() -> DocumentStateSharedFixtures.load(temp, "requested-case"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("caseName")
                .hasMessageContaining("requested-case")
                .hasMessageContaining("other-case");
    }

    @Test
    void Given_Shared_Fixture_Manifest_Path_Field_Is_Missing_Should_Fail_Fast()
            throws IOException {
        final Path fixture = createFixtureDirectory("case-with-missing-path");
        Files.writeString(fixture.resolve(FIXTURE_MANIFEST_FILE), "{"
                + "\"fixtureVersion\":" + jsonText(FIXTURE_VERSION) + ","
                + "\"caseName\":\"case-with-missing-path\","
                + "\"expectedPublicCdcDocumentPath\":" + jsonText(PUBLIC_DOCUMENT_FILE)
                + "}");

        assertThatThrownBy(() -> DocumentStateSharedFixtures.load(temp, "case-with-missing-path"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expectedCacheRowPath");
    }

    @ParameterizedTest
    @MethodSource("invalidManifestPathValues")
    void Given_Shared_Fixture_Manifest_Path_Value_Is_Invalid_Should_Fail_Fast(
            final String cacheRowPathValue,
            final String expectedMessage) throws IOException {
        final Path fixture = createFixtureDirectory("case-with-bad-path");
        writeManifest(
                fixture,
                "case-with-bad-path",
                cacheRowPathValue,
                jsonText(PUBLIC_DOCUMENT_FILE));

        assertThatThrownBy(() -> DocumentStateSharedFixtures.load(temp, "case-with-bad-path"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expectedCacheRowPath")
                .hasMessageContaining(expectedMessage);
    }

    @Test
    void Given_Shared_Fixture_Manifest_Path_Value_Is_Absolute_Should_Fail_Fast()
            throws IOException {
        final Path fixture = createFixtureDirectory("case-with-absolute-path");
        writeManifest(
                fixture,
                "case-with-absolute-path",
                jsonText(temp.resolve("outside.json").toString()),
                jsonText(PUBLIC_DOCUMENT_FILE));

        assertThatThrownBy(() -> DocumentStateSharedFixtures.load(temp, "case-with-absolute-path"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expectedCacheRowPath")
                .hasMessageContaining("relative");
    }

    @Test
    void Given_Shared_Fixture_Referenced_File_Does_Not_Exist_Should_Fail_Fast()
            throws IOException {
        final Path fixture = temp.resolve("case-with-missing-file");
        Files.createDirectories(fixture);
        writeManifest(fixture, "case-with-missing-file");
        Files.writeString(fixture.resolve(CACHE_ROW_FILE), validCacheRow());

        assertThatThrownBy(() -> DocumentStateSharedFixtures.load(temp, "case-with-missing-file"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Required shared DMS fixture file is missing")
                .hasMessageContaining(PUBLIC_DOCUMENT_FILE);
    }

    @ParameterizedTest
    @MethodSource("invalidRequiredJsonNodeTypes")
    void Given_Shared_Fixture_Required_Json_Node_Type_Is_Wrong_Should_Fail_Fast(
            final String cacheRowJson,
            final String publicDocumentJson,
            final String expectedMessage) throws IOException {
        final Path fixture = createFixtureDirectory("case-with-wrong-json-type");
        writeManifest(fixture, "case-with-wrong-json-type");
        Files.writeString(fixture.resolve(CACHE_ROW_FILE), cacheRowJson);
        Files.writeString(fixture.resolve(PUBLIC_DOCUMENT_FILE), publicDocumentJson);

        assertThatThrownBy(() -> DocumentStateSharedFixtures.load(temp, "case-with-wrong-json-type"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(expectedMessage);
    }

    private static Stream<Object[]> representativeSharedFixtures() {
        return Stream.of(
                representativeSharedFixture(ORDINARY_CASE, DocumentState.POSTGRESQL_PROVIDER, "schoolReference"),
                representativeSharedFixture(DESCRIPTOR_CASE, DocumentState.POSTGRESQL_PROVIDER, "namespace"),
                representativeSharedFixture(EXTENSION_CASE, DocumentState.POSTGRESQL_PROVIDER, "_ext"),
                representativeSharedFixture(PROPERTY_ABSENCE_CASE, DocumentState.POSTGRESQL_PROVIDER, "addresses"),
                representativeSharedFixture(ORDINARY_CASE, DocumentState.SQLSERVER_PROVIDER, "schoolReference"),
                representativeSharedFixture(DESCRIPTOR_CASE, DocumentState.SQLSERVER_PROVIDER, "namespace"),
                representativeSharedFixture(EXTENSION_CASE, DocumentState.SQLSERVER_PROVIDER, "_ext"),
                representativeSharedFixture(PROPERTY_ABSENCE_CASE, DocumentState.SQLSERVER_PROVIDER, "addresses"));
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

    private static Stream<Object[]> invalidManifestPathValues() {
        return Stream.of(
                new Object[] {"null", "non-blank text field"},
                new Object[] {jsonText(""), "non-blank text field"},
                new Object[] {jsonText("../" + CACHE_ROW_FILE), "relative"});
    }

    private static Stream<Object[]> invalidRequiredJsonNodeTypes() {
        return Stream.of(
                new Object[] {
                    cacheRowJson(jsonText("Ed-Fi"), jsonText("222"), documentJsonEntry(validDocumentJson())),
                    validPublicDocument(),
                    "contentVersion"
                },
                new Object[] {
                    cacheRowJson("123", "222", documentJsonEntry(validDocumentJson())),
                    validPublicDocument(),
                    "projectName"
                },
                new Object[] {
                    cacheRowJson(jsonText("Ed-Fi"), "222", ""),
                    validPublicDocument(),
                    "documentJson"
                },
                new Object[] {
                    cacheRowJson(jsonText("Ed-Fi"), "222", documentJsonEntry(jsonText("{}"))),
                    validPublicDocument(),
                    "documentJson"
                },
                new Object[] {
                    validCacheRow(),
                    "{\"document\":\"not-object\"}",
                    "document"
                });
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

    private static void assertPublicJsonBytes(final SourceRecord result) {
        assertThat(result.valueSchema().type()).isEqualTo(Schema.Type.BYTES);
        assertThat(result.valueSchema().name()).isEqualTo(DocumentStateJsonConverter.PUBLIC_SCHEMA_NAME);
        assertThat(result.valueSchema().version()).isEqualTo(DocumentStateJsonConverter.PUBLIC_SCHEMA_VERSION);
        assertThat(result.valueSchema().isOptional()).isFalse();
        assertThat(result.value()).isInstanceOf(byte[].class);
    }

    private static void assertNoExplicitNull(final JsonNode node, final String path) {
        assertThat(node.isNull()).as(path).isFalse();
        if (node.isObject()) {
            node.fields().forEachRemaining(entry ->
                    assertNoExplicitNull(entry.getValue(), path + "." + entry.getKey()));
        } else if (node.isArray()) {
            for (int index = 0; index < node.size(); index += 1) {
                assertNoExplicitNull(node.get(index), path + "[" + index + "]");
            }
        }
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

    private Path createFixtureDirectory(final String caseName) throws IOException {
        final Path fixture = temp.resolve(caseName);
        Files.createDirectories(fixture);
        return fixture;
    }

    private static void writeManifest(
            final Path fixture,
            final String caseName) throws IOException {
        writeManifest(fixture, caseName, jsonText(CACHE_ROW_FILE), jsonText(PUBLIC_DOCUMENT_FILE));
    }

    private static void writeManifest(
            final Path fixture,
            final String caseName,
            final String cacheRowPathValue,
            final String publicDocumentPathValue) throws IOException {
        Files.writeString(fixture.resolve(FIXTURE_MANIFEST_FILE), "{"
                + "\"fixtureVersion\":" + jsonText(FIXTURE_VERSION) + ","
                + "\"caseName\":" + jsonText(caseName) + ","
                + "\"expectedCacheRowPath\":" + cacheRowPathValue + ","
                + "\"expectedPublicCdcDocumentPath\":" + publicDocumentPathValue
                + "}");
    }

    private static String validCacheRow() {
        return cacheRowJson(
                jsonText("Ed-Fi"),
                "222",
                documentJsonEntry(validDocumentJson()));
    }

    private static String cacheRowJson(
            final String projectNameValue,
            final String contentVersionValue,
            final String documentJsonEntry) {
        return "{"
                + "\"documentUuid\":" + jsonText(DocumentStateTestRecords.DOCUMENT_UUID) + ","
                + "\"projectName\":" + projectNameValue + ","
                + "\"resourceName\":\"StudentSchoolAssociation\","
                + "\"resourceVersion\":\"1.0\","
                + "\"contentVersion\":" + contentVersionValue + ","
                + "\"lastModifiedAt\":\"2026-07-30T14:15:16.123456Z\","
                + "\"streamEtag\":\"222-01234567.j._.l.i\""
                + documentJsonEntry
                + "}";
    }

    private static String documentJsonEntry(final String documentJsonValue) {
        return ",\"documentJson\":" + documentJsonValue;
    }

    private static String validDocumentJson() {
        return "{"
                + "\"id\":" + jsonText(DocumentStateTestRecords.DOCUMENT_UUID) + ","
                + "\"_lastModifiedDate\":\"2026-07-30T14:15:16Z\","
                + "\"schoolId\":255901"
                + "}";
    }

    private static String validPublicDocument() {
        return "{"
                + "\"document\":{"
                + "\"id\":" + jsonText(DocumentStateTestRecords.DOCUMENT_UUID) + ","
                + "\"_lastModifiedDate\":\"2026-07-30T14:15:16Z\","
                + "\"schoolId\":255901,"
                + "\"_etag\":\"222-01234567.j._.l.i\""
                + "}"
                + "}";
    }

    private static String jsonText(final String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
