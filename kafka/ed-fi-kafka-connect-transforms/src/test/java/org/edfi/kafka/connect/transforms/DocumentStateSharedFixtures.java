// SPDX-License-Identifier: Apache-2.0
// Licensed to the Ed-Fi Alliance under one or more agreements.
// The Ed-Fi Alliance licenses this file to you under the Apache License, Version 2.0.
// See the LICENSE and NOTICES files in the project root for more information.

package org.edfi.kafka.connect.transforms;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.source.SourceRecord;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.edfi.kafka.connect.converters.DocumentStateJsonConverter;

final class DocumentStateSharedFixtures {

    static final String FIXTURE_ROOT_PROPERTY = "edfiDmsMaterializedDocumentFixtureRoot";

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .nodeFactory(JsonNodeFactory.withExactBigDecimals(true))
            .build();
    private static final String FIXTURE_MANIFEST_FILE = "fixture.json";
    private static final String FIXTURE_VERSION = "materialized-document-fixture-v1";
    private static final String FIXTURE_VERSION_FIELD = "fixtureVersion";
    private static final String CASE_NAME_FIELD = "caseName";
    private static final String EXPECTED_CACHE_ROW_PATH_FIELD = "expectedCacheRowPath";
    private static final String EXPECTED_PUBLIC_CDC_DOCUMENT_PATH_FIELD =
            "expectedPublicCdcDocumentPath";
    private static final String DOCUMENT_FIELD = "document";
    private static final String LAST_MODIFIED_DATE_FIELD = "_lastModifiedDate";

    private DocumentStateSharedFixtures() {
    }

    static SharedFixture load(final String caseName) throws IOException {
        return load(fixtureRoot(), caseName);
    }

    static SharedFixture load(final Path fixtureRoot, final String caseName) throws IOException {
        requireDirectory(fixtureRoot, "shared DMS fixture root");
        final Path fixtureDirectory = fixtureRoot.resolve(caseName);
        requireDirectory(fixtureDirectory, "shared DMS fixture case");
        final Path manifestPath = fixtureDirectory.resolve(FIXTURE_MANIFEST_FILE);
        final JsonNode manifest = readRequiredObject(manifestPath, "shared DMS fixture manifest");
        validateManifest(manifest, manifestPath, caseName);
        final Path cacheRowPath =
                resolveManifestPath(fixtureDirectory, manifestPath, manifest, EXPECTED_CACHE_ROW_PATH_FIELD);
        final Path publicDocumentPath = resolveManifestPath(
                fixtureDirectory, manifestPath, manifest, EXPECTED_PUBLIC_CDC_DOCUMENT_PATH_FIELD);
        final JsonNode cacheRow = readRequiredObject(cacheRowPath, "shared DMS expected cache row");
        final JsonNode publicDocument =
                readRequiredObject(publicDocumentPath, "shared DMS expected public CDC document");
        final JsonNode expectedDocument = requiredObject(publicDocument, DOCUMENT_FIELD, publicDocumentPath);
        return new SharedFixture(
                caseName,
                new CacheRowData(cacheRow, cacheRowPath),
                expectedDocument,
                requiredText(expectedDocument, LAST_MODIFIED_DATE_FIELD, publicDocumentPath));
    }

    static JsonNode toJson(final Object value) throws IOException {
        return MAPPER.readTree(MAPPER.writeValueAsString(value));
    }

    static JsonNode serializedPublicValue(final SourceRecord record) throws IOException {
        return MAPPER.readTree(serializedPublicValueBytes(record));
    }

    static String serializedPublicValueText(final SourceRecord record) {
        return new String(serializedPublicValueBytes(record), StandardCharsets.UTF_8);
    }

    private static byte[] serializedPublicValueBytes(final SourceRecord record) {
        final DocumentStateJsonConverter converter = new DocumentStateJsonConverter();
        converter.configure(Map.of(
                "schemas.enable", "false",
                "decimal.format", "NUMERIC"), false);
        return converter.fromConnectData(record.topic(), record.valueSchema(), record.value());
    }

    private static Path fixtureRoot() {
        final String fixtureRoot = System.getProperty(FIXTURE_ROOT_PROPERTY);
        if (fixtureRoot == null || fixtureRoot.isBlank()) {
            throw new IllegalStateException("Gradle property " + FIXTURE_ROOT_PROPERTY
                    + " is required and must point to DMS src/dms/backend/Fixtures/"
                    + "document-cache/materialized-documents");
        }
        final Path path = Path.of(fixtureRoot);
        requireDirectory(path, "shared DMS fixture root");
        return path;
    }

    private static JsonNode readRequiredJson(final Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("Required shared DMS fixture file is missing: " + path);
        }
        return MAPPER.readTree(path.toFile());
    }

    private static JsonNode readRequiredObject(
            final Path path,
            final String description) throws IOException {
        final JsonNode node = readRequiredJson(path);
        if (node == null || !node.isObject()) {
            throw new IllegalStateException(description + " must be a JSON object: " + path);
        }
        return node;
    }

    private static void requireDirectory(final Path path, final String description) {
        if (!Files.isDirectory(path)) {
            throw new IllegalStateException(description + " does not exist: " + path);
        }
    }

    private static void validateManifest(
            final JsonNode manifest,
            final Path manifestPath,
            final String caseName) {
        final String fixtureVersion = requiredText(manifest, FIXTURE_VERSION_FIELD, manifestPath);
        if (!FIXTURE_VERSION.equals(fixtureVersion)) {
            throw new IllegalStateException("Shared fixture manifest fixtureVersion mismatch for "
                    + manifestPath + ": expected " + FIXTURE_VERSION + " but found " + fixtureVersion);
        }
        final String manifestCaseName = requiredText(manifest, CASE_NAME_FIELD, manifestPath);
        if (!caseName.equals(manifestCaseName)) {
            throw new IllegalStateException("Shared fixture manifest caseName mismatch for "
                    + manifestPath + ": expected " + caseName + " but found " + manifestCaseName);
        }
    }

    private static Path resolveManifestPath(
            final Path fixtureDirectory,
            final Path manifestPath,
            final JsonNode manifest,
            final String fieldName) {
        final String relativePath = requiredText(manifest, fieldName, manifestPath);
        final Path path = Path.of(relativePath);
        if (path.isAbsolute() || containsParentDirectory(path)) {
            throw new IllegalStateException("Shared fixture manifest path must stay relative to "
                    + fixtureDirectory + ": " + fieldName + "=" + relativePath);
        }
        return fixtureDirectory.resolve(path).normalize();
    }

    private static boolean containsParentDirectory(final Path path) {
        for (final Path name : path) {
            if ("..".equals(name.toString())) {
                return true;
            }
        }
        return false;
    }

    private static String requiredText(
            final JsonNode object,
            final String fieldName,
            final Path sourcePath) {
        final JsonNode value = object.get(fieldName);
        if (value == null || value.isNull() || !value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalStateException("Shared fixture file must contain non-blank text field "
                    + fieldName + ": " + sourcePath);
        }
        return value.textValue();
    }

    private static long requiredLong(
            final JsonNode object,
            final String fieldName,
            final Path sourcePath) {
        final JsonNode value = object.get(fieldName);
        if (value == null || value.isNull() || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new IllegalStateException("Shared fixture file must contain signed 64-bit integer field "
                    + fieldName + ": " + sourcePath);
        }
        return value.longValue();
    }

    private static JsonNode requiredObject(
            final JsonNode object,
            final String fieldName,
            final Path sourcePath) {
        final JsonNode value = object.get(fieldName);
        if (value == null || !value.isObject()) {
            throw new IllegalStateException("Shared fixture file must contain object field "
                    + fieldName + ": " + sourcePath);
        }
        return value;
    }

    static final class SharedFixture {
        private final String caseName;
        private final CacheRowData cacheRow;
        private final JsonNode expectedDocument;
        private final String expectedLastModifiedAt;
        private final JsonNode expectedEnvelope;

        private SharedFixture(
                final String caseName,
                final CacheRowData cacheRow,
                final JsonNode expectedDocument,
                final String expectedLastModifiedAt) {
            this.caseName = caseName;
            this.cacheRow = cacheRow;
            this.expectedDocument = expectedDocument;
            this.expectedLastModifiedAt = expectedLastModifiedAt;
            this.expectedEnvelope = createExpectedEnvelope();
        }

        String caseName() {
            return caseName;
        }

        String documentUuid() {
            return cacheRow.documentUuid;
        }

        JsonNode cacheRow() {
            return cacheRow.json;
        }

        JsonNode expectedDocument() {
            return expectedDocument;
        }

        JsonNode expectedEnvelope() {
            return expectedEnvelope;
        }

        Struct cacheRowStruct(final String provider) throws IOException {
            return DocumentStateTestRecords
                    .cacheRowBuilder(provider)
                    .field(DocumentStateTestRecords.DOCUMENT_UUID_FIELD,
                            DocumentStateTestRecords.pinnedUuidSchema(provider), documentUuid())
                    .field(DocumentStateTestRecords.PROJECT_NAME_FIELD, Schema.STRING_SCHEMA,
                            cacheRow.projectName)
                    .field(DocumentStateTestRecords.RESOURCE_NAME_FIELD, Schema.STRING_SCHEMA,
                            cacheRow.resourceName)
                    .field(DocumentStateTestRecords.RESOURCE_VERSION_FIELD, Schema.STRING_SCHEMA,
                            cacheRow.resourceVersion)
                    .field(DocumentStateTestRecords.CONTENT_VERSION_FIELD, Schema.INT64_SCHEMA,
                            cacheRow.contentVersion)
                    .field(DocumentStateTestRecords.STREAM_ETAG_FIELD, Schema.STRING_SCHEMA,
                            cacheRow.streamEtag)
                    .field(DocumentStateTestRecords.LAST_MODIFIED_AT_FIELD,
                            DocumentStateTestRecords.lastModifiedAtSchema(provider),
                            cacheRow.lastModifiedAt)
                    .field(DocumentStateTestRecords.DOCUMENT_JSON_FIELD,
                            DocumentStateTestRecords.documentJsonSchema(provider),
                            MAPPER.writeValueAsString(cacheRow.documentJson))
                    .build();
        }

        SourceRecord publicUpsertRecord(final String provider) throws IOException {
            return DocumentStateTestRecords.documentCacheRecordWithPublicMetadata(
                    provider, documentUuid().toUpperCase(Locale.ROOT), cacheRowStruct(provider));
        }

        private JsonNode createExpectedEnvelope() {
            final ObjectNode expected = MAPPER.createObjectNode();
            expected.put("contractVersion", 1);
            expected.put("documentUuid", cacheRow.documentUuid);
            expected.put("projectName", cacheRow.projectName);
            expected.put("resourceName", cacheRow.resourceName);
            expected.put("resourceVersion", cacheRow.resourceVersion);
            expected.put("contentVersion", cacheRow.contentVersion);
            expected.put("lastModifiedAt", expectedLastModifiedAt);
            expected.set("document", expectedDocument);
            return expected;
        }
    }

    private static final class CacheRowData {
        private final JsonNode json;
        private final String documentUuid;
        private final String projectName;
        private final String resourceName;
        private final String resourceVersion;
        private final long contentVersion;
        private final String streamEtag;
        private final String lastModifiedAt;
        private final JsonNode documentJson;

        private CacheRowData(final JsonNode json, final Path path) {
            this.json = json;
            this.documentUuid = requiredText(json, "documentUuid", path);
            this.projectName = requiredText(json, "projectName", path);
            this.resourceName = requiredText(json, "resourceName", path);
            this.resourceVersion = requiredText(json, "resourceVersion", path);
            this.contentVersion = requiredLong(json, "contentVersion", path);
            this.streamEtag = requiredText(json, "streamEtag", path);
            this.lastModifiedAt = requiredText(json, "lastModifiedAt", path);
            this.documentJson = requiredObject(json, "documentJson", path);
        }
    }
}
