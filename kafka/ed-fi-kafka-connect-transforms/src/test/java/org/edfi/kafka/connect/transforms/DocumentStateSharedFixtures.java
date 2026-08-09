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
import org.apache.kafka.connect.json.JsonConverter;
import org.apache.kafka.connect.source.SourceRecord;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

final class DocumentStateSharedFixtures {

    static final String FIXTURE_ROOT_PROPERTY = "edfiDmsMaterializedDocumentFixtureRoot";

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .nodeFactory(JsonNodeFactory.withExactBigDecimals(true))
            .build();
    private static final String CACHE_ROW_FILE = "expected-cache-row.json";
    private static final String PUBLIC_DOCUMENT_FILE = "expected-public-cdc-document.json";

    private DocumentStateSharedFixtures() {
    }

    static SharedFixture load(final String caseName) throws IOException {
        return load(fixtureRoot(), caseName);
    }

    static SharedFixture load(final Path fixtureRoot, final String caseName) throws IOException {
        requireDirectory(fixtureRoot, "shared DMS fixture root");
        final Path fixtureDirectory = fixtureRoot.resolve(caseName);
        final JsonNode cacheRow = readRequiredJson(fixtureDirectory.resolve(CACHE_ROW_FILE));
        final JsonNode publicDocument = readRequiredJson(fixtureDirectory.resolve(PUBLIC_DOCUMENT_FILE));
        final JsonNode expectedDocument = publicDocument.get("document");
        if (expectedDocument == null || !expectedDocument.isObject()) {
            throw new IllegalStateException("Shared fixture file must contain document object: "
                    + fixtureDirectory.resolve(PUBLIC_DOCUMENT_FILE));
        }
        return new SharedFixture(caseName, cacheRow, expectedDocument);
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
        final JsonConverter converter = new JsonConverter();
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

    private static void requireDirectory(final Path path, final String description) {
        if (!Files.isDirectory(path)) {
            throw new IllegalStateException(description + " does not exist: " + path);
        }
    }

    static final class SharedFixture {
        private final String caseName;
        private final JsonNode cacheRow;
        private final JsonNode expectedDocument;
        private final JsonNode expectedEnvelope;

        private SharedFixture(
                final String caseName,
                final JsonNode cacheRow,
                final JsonNode expectedDocument) {
            this.caseName = caseName;
            this.cacheRow = cacheRow;
            this.expectedDocument = expectedDocument;
            this.expectedEnvelope = expectedEnvelope(cacheRow, expectedDocument);
        }

        String caseName() {
            return caseName;
        }

        String documentUuid() {
            return cacheRow.get("documentUuid").asText();
        }

        JsonNode cacheRow() {
            return cacheRow;
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

        SourceRecord publicUpsertRecord(final String provider) throws IOException {
            return DocumentStateTestRecords.documentCacheRecordWithPublicMetadata(
                    provider, documentUuid().toUpperCase(Locale.ROOT), cacheRowStruct(provider));
        }

        private static JsonNode expectedEnvelope(
                final JsonNode cacheRow,
                final JsonNode expectedDocument) {
            final ObjectNode expected = MAPPER.createObjectNode();
            expected.put("contractVersion", 1);
            expected.put("documentUuid", cacheRow.get("documentUuid").asText());
            expected.put("projectName", cacheRow.get("projectName").asText());
            expected.put("resourceName", cacheRow.get("resourceName").asText());
            expected.put("resourceVersion", cacheRow.get("resourceVersion").asText());
            expected.put("contentVersion", cacheRow.get("contentVersion").asLong());
            expected.put("lastModifiedAt", expectedDocument.get("_lastModifiedDate").asText());
            expected.set("document", expectedDocument);
            return expected;
        }
    }
}
