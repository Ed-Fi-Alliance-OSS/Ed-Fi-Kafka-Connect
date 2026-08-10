// SPDX-License-Identifier: Apache-2.0
// Licensed to the Ed-Fi Alliance under one or more agreements.
// The Ed-Fi Alliance licenses this file to you under the Apache License, Version 2.0.
// See the LICENSE and NOTICES files in the project root for more information.

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.header.ConnectHeaders;
import org.apache.kafka.connect.header.Header;
import org.apache.kafka.connect.header.Headers;
import org.apache.kafka.connect.source.SourceRecord;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import org.edfi.kafka.connect.converters.DocumentStateJsonConverter;
import org.edfi.kafka.connect.transforms.DocumentState;

// CI execution smoke, run inside the built Ed-Fi image by .github/workflows/on-pullrequest.yml.
// Unit tests prove the detailed behavior. This proves the packaged DocumentState artifact loads
// and executes representative retained records on the pinned Debezium 3.6 / Kafka Connect 4.3
// runtime classpath.
public final class DocumentStateExecutionSmoke {

    private static final String TARGET_TOPIC = "edfi.documents";
    private static final String PROGRESS_TOPIC = "edfi.documents.cdc-progress";
    private static final String DOCUMENT_UUID = "aaaaaaaa-bbbb-cccc-dddd-000000000101";
    private static final String POSTGRESQL_SOURCE_SCHEMA = "io.debezium.connector.postgresql.Source";
    private static final String POSTGRESQL_UUID_SCHEMA = "io.debezium.data.Uuid";
    private static final String POSTGRESQL_JSON_SCHEMA = "io.debezium.data.Json";
    private static final String POSTGRESQL_TIMESTAMP_SCHEMA = "io.debezium.time.ZonedTimestamp";
    private static final String VALUE_CONVERTER_CONFIG = "value.converter";
    private static final String VALUE_CONVERTER_SCHEMAS_CONFIG = "value.converter.schemas.enable";
    private static final String VALUE_CONVERTER_DECIMAL_CONFIG = "value.converter.decimal.format";
    private static final Map<String, String> CONNECTOR_VALUE_CONVERTER_CONFIG = Map.of(
            VALUE_CONVERTER_CONFIG, DocumentStateJsonConverter.class.getName(),
            VALUE_CONVERTER_SCHEMAS_CONFIG, "false",
            VALUE_CONVERTER_DECIMAL_CONFIG, "NUMERIC");
    private static final BigDecimal HIGH_PRECISION_DECIMAL =
            new BigDecimal("3.141592653589793238462643383279");
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
            .nodeFactory(JsonNodeFactory.withExactBigDecimals(true))
            .build();

    private DocumentStateExecutionSmoke() {
    }

    public static void main(final String[] args) throws IOException {
        if (args.length != 1) {
            fail("usage: DocumentStateExecutionSmoke <school-address-property-absence fixture directory>");
        }

        final SharedCacheFixture sharedFixture = sharedCacheFixture(Path.of(args[0]));
        smokePublicUpsert(sharedFixture);
        smokePublicUpsertDecimalSerialization();
        smokePublicTombstone();
        smokeProgressRecord();
        smokeMalformedRetainedRecordFailure();
        System.out.println("OK: DocumentState and DocumentStateJsonConverter executed representative records "
                + "on the image runtime classpath.");
    }

    private static void smokePublicUpsert(final SharedCacheFixture sharedFixture) throws IOException {
        final DocumentState<SourceRecord> transform = configuredTransform();
        try {
            final SourceRecord record = documentCacheRecord(
                    sharedFixture,
                    new ConnectHeaders().addString("source-header", "stripped"));

            final SourceRecord out = transform.apply(record);

            expect(TARGET_TOPIC.equals(out.topic()), "upsert topic = " + out.topic());
            expect(out.kafkaPartition() == null, "upsert partition");
            expect(out.keySchema() == Schema.STRING_SCHEMA, "upsert key schema");
            expect(sharedFixture.documentUuid().equals(out.key()), "upsert key = " + out.key());
            expectPublicValueSchema(out, "upsert");
            expect(out.timestamp() == null, "upsert timestamp");
            expect(!out.headers().iterator().hasNext(), "upsert headers stripped");
            expect(record.sourcePartition().equals(out.sourcePartition()), "upsert source partition");
            expect(record.sourceOffset().equals(out.sourceOffset()), "upsert source offset");

            final byte[] serialized = serializedPublicValue(out);
            final String serializedText = new String(serialized, StandardCharsets.UTF_8);
            expect(!serializedText.contains("\"schema\""), "upsert has no schema wrapper");
            expect(!serializedText.contains("\"payload\""), "upsert has no payload wrapper");
            expect(!serializedText.startsWith("\""), "upsert is not a JSON-quoted or Base64 whole value");

            final JsonNode root = MAPPER.readTree(serialized);
            expect(root.isObject(), "upsert root is a JSON object");
            expectEnvelope(root, sharedFixture, "upsert");

            final JsonNode document = root.get("document");
            expect(sharedFixture.expectedPublicDocument().equals(document), "upsert shared public document");
            final JsonNode addresses = document.get("addresses");
            expect(addresses != null && addresses.isArray() && addresses.size() == 2, "upsert addresses");
            expect(addresses.get(0).has("addressTypeDescriptor"), "upsert first address descriptor");
            expect(!addresses.get(1).has("addressTypeDescriptor"), "upsert second address descriptor absent");
        } finally {
            transform.close();
        }
    }

    private static void smokePublicUpsertDecimalSerialization() throws IOException {
        final DocumentState<SourceRecord> transform = configuredTransform();
        try {
            final SourceRecord record = documentCacheRecord(
                    cacheRow(decimalDocumentJson(DOCUMENT_UUID)), 988L,
                    new ConnectHeaders().addString("source-header", "stripped"));

            final SourceRecord out = transform.apply(record);

            expect(TARGET_TOPIC.equals(out.topic()), "decimal upsert topic = " + out.topic());
            expect(out.keySchema() == Schema.STRING_SCHEMA, "decimal upsert key schema");
            expect(DOCUMENT_UUID.equals(out.key()), "decimal upsert key = " + out.key());
            expectPublicValueSchema(out, "decimal upsert");

            final byte[] serialized = serializedPublicValue(out);
            final String serializedText = new String(serialized, StandardCharsets.UTF_8);
            expect(!serializedText.contains("\"schema\""), "decimal upsert has no schema wrapper");
            expect(!serializedText.contains("\"payload\""), "decimal upsert has no payload wrapper");
            expect(!serializedText.startsWith("\""), "decimal upsert is not a JSON-quoted whole value");
            expect(serializedText.contains("\"gpa\":" + HIGH_PRECISION_DECIMAL.toPlainString()),
                    "decimal upsert gpa is unquoted in " + serializedText);
            expect(!serializedText.contains("\"gpa\":\""), "decimal upsert gpa is not a string");

            final JsonNode root = MAPPER.readTree(serialized);
            expect(root.isObject(), "decimal upsert root is a JSON object");
            expect(!root.has("schema"), "decimal upsert parsed has no schema wrapper");
            expect(!root.has("payload"), "decimal upsert parsed has no payload wrapper");
            final JsonNode sampleExtension = root.get("document").get("_ext").get("sample");
            expectNumericDecimal(sampleExtension.get("gpa"), HIGH_PRECISION_DECIMAL, "decimal gpa");
            expectNumericDecimal(
                    sampleExtension.get("academicSummary").get("weightedGpa"),
                    HIGH_PRECISION_DECIMAL,
                    "decimal weightedGpa");
            expectNumericDecimal(
                    sampleExtension.get("scoreHistory").get(0),
                    HIGH_PRECISION_DECIMAL,
                    "decimal scoreHistory");
        } finally {
            transform.close();
        }
    }

    private static void smokePublicTombstone() {
        final DocumentState<SourceRecord> transform = configuredTransform();
        try {
            final SourceRecord record = documentDeleteRecord(
                    DOCUMENT_UUID.toUpperCase(), documentBeforeRow(DOCUMENT_UUID), 654L,
                    new ConnectHeaders().addString("source-header", "stripped"));

            final SourceRecord out = transform.apply(record);

            expect(TARGET_TOPIC.equals(out.topic()), "tombstone topic = " + out.topic());
            expect(out.kafkaPartition() == null, "tombstone partition");
            expect(out.keySchema() == Schema.STRING_SCHEMA, "tombstone key schema");
            expect(DOCUMENT_UUID.equals(out.key()), "tombstone key = " + out.key());
            expect(out.valueSchema() == null, "tombstone value schema");
            expect(out.value() == null, "tombstone value");
            expect(serializedPublicValue(out) == null, "tombstone serialized null");
            expect(out.timestamp() == null, "tombstone timestamp");
            expect(!out.headers().iterator().hasNext(), "tombstone headers stripped");
            expect(record.sourcePartition().equals(out.sourcePartition()), "tombstone source partition");
            expect(record.sourceOffset().equals(out.sourceOffset()), "tombstone source offset");
        } finally {
            transform.close();
        }
    }

    private static void smokeProgressRecord() {
        final DocumentState<SourceRecord> transform = configuredTransform();
        try {
            final Headers headers = new ConnectHeaders().addString("source-header", "kept");
            final SourceRecord record = new SourceRecord(
                    sourcePartition(), sourceOffset(), "__debezium-heartbeat.dms", null,
                    Schema.STRING_SCHEMA, "ignored-source-key", Schema.STRING_SCHEMA, "heartbeat", 321L,
                    headers);

            final SourceRecord out = transform.apply(record);

            expect(PROGRESS_TOPIC.equals(out.topic()), "progress topic = " + out.topic());
            expect(out.keySchema() == Schema.STRING_SCHEMA, "progress key schema");
            expect("cdc-progress".equals(out.key()), "progress key = " + out.key());
            expect(out.valueSchema() == Schema.STRING_SCHEMA, "progress value schema");
            expect("heartbeat".equals(out.value()), "progress value");
            expect("\"heartbeat\"".equals(new String(serializedPublicValue(out), StandardCharsets.UTF_8)),
                    "progress serialized through JsonConverter delegate");
            expect(Long.valueOf(321L).equals(out.timestamp()), "progress timestamp");
            final Header sourceHeader = out.headers().lastWithName("source-header");
            expect(sourceHeader != null, "progress source-header present");
            expect("source-header".equals(sourceHeader.key()), "progress header name");
            expect(sourceHeader.schema() == Schema.STRING_SCHEMA, "progress header schema");
            expect("kept".equals(sourceHeader.value()), "progress header value");
            expect(record.sourcePartition().equals(out.sourcePartition()), "progress source partition");
            expect(record.sourceOffset().equals(out.sourceOffset()), "progress source offset");
        } finally {
            transform.close();
        }
    }

    private static void smokeMalformedRetainedRecordFailure() {
        final DocumentState<SourceRecord> transform = configuredTransform();
        try {
            transform.apply(documentCacheRecord(cacheRow("[]"), null, new ConnectHeaders()));
            fail("malformed retained record should fail");
        } catch (final DocumentState.TransformationFailureException e) {
            expect(e.reason() == DocumentState.FailureReason.INVALID_DOCUMENT_JSON,
                    "failure reason = " + e.reason());
            expect("DocumentCache".equals(e.metadata().get("sourceTable")),
                    "failure sourceTable = " + e.metadata());
        } finally {
            transform.close();
        }
    }

    private static DocumentState<SourceRecord> configuredTransform() {
        final DocumentState<SourceRecord> transform = new DocumentState<>();
        transform.configure(Map.of(
                DocumentState.PROVIDER_CONFIG, DocumentState.POSTGRESQL_PROVIDER,
                DocumentState.TARGET_TOPIC_CONFIG, TARGET_TOPIC,
                DocumentState.PROGRESS_TOPIC_CONFIG, PROGRESS_TOPIC));
        return transform;
    }

    private static SourceRecord documentCacheRecord(
            final Struct after,
            final Long timestamp,
            final Headers headers) {
        return documentCacheRecord(DOCUMENT_UUID, after, timestamp, headers);
    }

    private static SourceRecord documentCacheRecord(
            final SharedCacheFixture sharedFixture,
            final Headers headers) {
        return documentCacheRecord(sharedFixture.documentUuid(), sharedFixture.cacheRow(), 987L, headers);
    }

    private static SourceRecord documentCacheRecord(
            final String documentUuid,
            final Struct after,
            final Long timestamp,
            final Headers headers) {
        return relationalRecord("DocumentCache", "c", keyStruct(documentUuid), "after", after, timestamp,
                headers);
    }

    private static SourceRecord documentDeleteRecord(
            final String documentUuid,
            final Struct before,
            final Long timestamp,
            final Headers headers) {
        return relationalRecord("Document", "d", keyStruct(documentUuid), "before", before, timestamp, headers);
    }

    private static SourceRecord relationalRecord(
            final String sourceTable,
            final String operation,
            final Struct key,
            final String rowField,
            final Struct row,
            final Long timestamp,
            final Headers headers) {
        final Schema sourceSchema = sourceSchema();
        final Schema valueSchema = SchemaBuilder.struct()
                .field("source", sourceSchema)
                .field("op", Schema.STRING_SCHEMA)
                .field(rowField, row.schema())
                .build();
        final Struct value = new Struct(valueSchema)
                .put("source", source(sourceSchema, sourceTable))
                .put("op", operation)
                .put(rowField, row);

        return new SourceRecord(
                sourcePartition(), sourceOffset(), "server.dms." + sourceTable, null,
                key.schema(), key, valueSchema, value, timestamp, headers);
    }

    private static Struct keyStruct(final String documentUuid) {
        final Schema schema = SchemaBuilder.struct()
                .name("server.dms." + POSTGRESQL_SOURCE_SCHEMA + ".Key")
                .field("DocumentUuid", postgresqlUuidSchema())
                .build();
        return new Struct(schema).put("DocumentUuid", documentUuid);
    }

    private static Struct cacheRow(final String documentJson) {
        return cacheRow(
                DOCUMENT_UUID,
                "Ed-Fi",
                "StudentSchoolAssociation",
                "1.0",
                222L,
                "222-01234567.j._.l.i",
                "2026-07-30T14:15:16.123456Z",
                documentJson);
    }

    private static Struct cacheRow(
            final String documentUuid,
            final String projectName,
            final String resourceName,
            final String resourceVersion,
            final long contentVersion,
            final String streamEtag,
            final String lastModifiedAt,
            final String documentJson) {
        final Schema schema = SchemaBuilder.struct()
                .name("server.dms." + POSTGRESQL_SOURCE_SCHEMA + ".DocumentCache.Value")
                .field("DocumentUuid", postgresqlUuidSchema())
                .field("ProjectName", Schema.STRING_SCHEMA)
                .field("ResourceName", Schema.STRING_SCHEMA)
                .field("ResourceVersion", Schema.STRING_SCHEMA)
                .field("ContentVersion", Schema.INT64_SCHEMA)
                .field("StreamEtag", Schema.STRING_SCHEMA)
                .field("LastModifiedAt", SchemaBuilder.string().name(POSTGRESQL_TIMESTAMP_SCHEMA).build())
                .field("DocumentJson", SchemaBuilder.string().name(POSTGRESQL_JSON_SCHEMA).build())
                .build();
        return new Struct(schema)
                .put("DocumentUuid", documentUuid)
                .put("ProjectName", projectName)
                .put("ResourceName", resourceName)
                .put("ResourceVersion", resourceVersion)
                .put("ContentVersion", contentVersion)
                .put("StreamEtag", streamEtag)
                .put("LastModifiedAt", lastModifiedAt)
                .put("DocumentJson", documentJson);
    }

    private static Struct documentBeforeRow(final String documentUuid) {
        final Schema schema = SchemaBuilder.struct()
                .name("server.dms." + POSTGRESQL_SOURCE_SCHEMA + ".Document.Value")
                .field("DocumentUuid", postgresqlUuidSchema())
                .build();
        return new Struct(schema).put("DocumentUuid", documentUuid);
    }

    private static Schema sourceSchema() {
        return SchemaBuilder.struct()
                .name(POSTGRESQL_SOURCE_SCHEMA)
                .field("schema", Schema.STRING_SCHEMA)
                .field("table", Schema.STRING_SCHEMA)
                .build();
    }

    private static Struct source(final Schema sourceSchema, final String sourceTable) {
        return new Struct(sourceSchema)
                .put("schema", "dms")
                .put("table", sourceTable);
    }

    private static Schema postgresqlUuidSchema() {
        return SchemaBuilder.string().name(POSTGRESQL_UUID_SCHEMA).build();
    }

    private static Map<String, String> sourcePartition() {
        return Map.of("server", "dms");
    }

    private static Map<String, Long> sourceOffset() {
        return Map.of("position", 1L);
    }

    private static String decimalDocumentJson(final String documentUuid) {
        return "{\"id\":\"" + documentUuid
                + "\",\"_lastModifiedDate\":\"2026-07-30T14:15:16Z\","
                + "\"_ext\":{\"sample\":{\"gpa\":"
                + HIGH_PRECISION_DECIMAL.toPlainString()
                + ",\"academicSummary\":{\"weightedGpa\":"
                + HIGH_PRECISION_DECIMAL.toPlainString()
                + "},\"scoreHistory\":["
                + HIGH_PRECISION_DECIMAL.toPlainString()
                + "]}}}";
    }

    private static byte[] serializedPublicValue(final SourceRecord record) {
        expect(DocumentStateJsonConverter.class.getName().equals(
                        CONNECTOR_VALUE_CONVERTER_CONFIG.get(VALUE_CONVERTER_CONFIG)),
                "value.converter config");
        expect("false".equals(CONNECTOR_VALUE_CONVERTER_CONFIG.get(VALUE_CONVERTER_SCHEMAS_CONFIG)),
                "value.converter.schemas.enable config");
        expect("NUMERIC".equals(CONNECTOR_VALUE_CONVERTER_CONFIG.get(VALUE_CONVERTER_DECIMAL_CONFIG)),
                "value.converter.decimal.format config");

        final DocumentStateJsonConverter converter = new DocumentStateJsonConverter();
        converter.configure(Map.of(
                "schemas.enable", CONNECTOR_VALUE_CONVERTER_CONFIG.get(VALUE_CONVERTER_SCHEMAS_CONFIG),
                "decimal.format", CONNECTOR_VALUE_CONVERTER_CONFIG.get(VALUE_CONVERTER_DECIMAL_CONFIG)),
                false);
        return converter.fromConnectData(record.topic(), record.valueSchema(), record.value());
    }

    private static void expectPublicValueSchema(final SourceRecord record, final String detail) {
        expect(record.valueSchema() != null, detail + " value schema");
        expect(record.valueSchema().type() == Schema.Type.BYTES, detail + " value schema type");
        expect(DocumentStateJsonConverter.PUBLIC_SCHEMA_NAME.equals(record.valueSchema().name()),
                detail + " value schema name");
        expect(Integer.valueOf(DocumentStateJsonConverter.PUBLIC_SCHEMA_VERSION).equals(
                        record.valueSchema().version()),
                detail + " value schema version");
        expect(record.value() instanceof byte[], detail + " value bytes");
    }

    private static void expectEnvelope(
            final JsonNode root,
            final SharedCacheFixture sharedFixture,
            final String detail) {
        expect(root.get("contractVersion").isIntegralNumber() && root.get("contractVersion").intValue() == 1,
                detail + " contractVersion");
        expect(sharedFixture.documentUuid().equals(root.get("documentUuid").textValue()),
                detail + " documentUuid");
        expect(sharedFixture.projectName().equals(root.get("projectName").textValue()),
                detail + " projectName");
        expect(sharedFixture.resourceName().equals(root.get("resourceName").textValue()),
                detail + " resourceName");
        expect(sharedFixture.resourceVersion().equals(root.get("resourceVersion").textValue()),
                detail + " resourceVersion");
        expect(root.get("contentVersion").isIntegralNumber()
                        && root.get("contentVersion").longValue() == sharedFixture.contentVersion(),
                detail + " contentVersion");
        expect(sharedFixture.normalizedLastModifiedAt().equals(root.get("lastModifiedAt").textValue()),
                detail + " lastModifiedAt");
    }

    private static SharedCacheFixture sharedCacheFixture(final Path fixtureDirectory) throws IOException {
        final JsonNode cacheRow = MAPPER.readTree(Files.readString(
                fixtureDirectory.resolve("expected-cache-row.json"), StandardCharsets.UTF_8));
        final JsonNode expectedPublicDocument = MAPPER.readTree(Files.readString(
                fixtureDirectory.resolve("expected-public-cdc-document.json"), StandardCharsets.UTF_8))
                .get("document");
        expect(expectedPublicDocument != null && expectedPublicDocument.isObject(), "shared fixture document");
        return new SharedCacheFixture(
                cacheRow.get("documentUuid").textValue(),
                cacheRow.get("projectName").textValue(),
                cacheRow.get("resourceName").textValue(),
                cacheRow.get("resourceVersion").textValue(),
                cacheRow.get("contentVersion").longValue(),
                cacheRow.get("streamEtag").textValue(),
                cacheRow.get("lastModifiedAt").textValue(),
                MAPPER.writeValueAsString(cacheRow.get("documentJson")),
                expectedPublicDocument);
    }

    private static void expectNumericDecimal(
            final JsonNode node,
            final BigDecimal expected,
            final String detail) {
        expect(node != null, detail + " present");
        expect(node.isNumber(), detail + " is numeric");
        expect(!node.isTextual(), detail + " is not textual");
        expect(expected.compareTo(node.decimalValue()) == 0, detail + " = " + node);
        expect(expected.toPlainString().equals(node.decimalValue().toPlainString()), detail + " exact text");
    }

    private static void expect(final boolean condition, final String detail) {
        if (!condition) {
            fail(detail);
        }
    }

    private static void fail(final String detail) {
        throw new IllegalStateException("DocumentState execution smoke failed: " + detail);
    }

    private static final class SharedCacheFixture {
        private final String documentUuid;
        private final String projectName;
        private final String resourceName;
        private final String resourceVersion;
        private final long contentVersion;
        private final JsonNode expectedPublicDocument;
        private final Struct cacheRow;

        SharedCacheFixture(
                final String documentUuid,
                final String projectName,
                final String resourceName,
                final String resourceVersion,
                final long contentVersion,
                final String streamEtag,
                final String lastModifiedAt,
                final String documentJson,
                final JsonNode expectedPublicDocument) {
            this.documentUuid = documentUuid;
            this.projectName = projectName;
            this.resourceName = resourceName;
            this.resourceVersion = resourceVersion;
            this.contentVersion = contentVersion;
            this.expectedPublicDocument = expectedPublicDocument;
            this.cacheRow = DocumentStateExecutionSmoke.cacheRow(
                    documentUuid,
                    projectName,
                    resourceName,
                    resourceVersion,
                    contentVersion,
                    streamEtag,
                    lastModifiedAt,
                    documentJson);
        }

        String documentUuid() {
            return documentUuid;
        }

        String projectName() {
            return projectName;
        }

        String resourceName() {
            return resourceName;
        }

        String resourceVersion() {
            return resourceVersion;
        }

        long contentVersion() {
            return contentVersion;
        }

        String normalizedLastModifiedAt() {
            return expectedPublicDocument.get("_lastModifiedDate").textValue();
        }

        JsonNode expectedPublicDocument() {
            return expectedPublicDocument;
        }

        Struct cacheRow() {
            return cacheRow;
        }
    }
}
