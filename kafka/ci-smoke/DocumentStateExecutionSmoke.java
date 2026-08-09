// SPDX-License-Identifier: Apache-2.0
// Licensed to the Ed-Fi Alliance under one or more agreements.
// The Ed-Fi Alliance licenses this file to you under the Apache License, Version 2.0.
// See the LICENSE and NOTICES files in the project root for more information.

import java.util.Map;

import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.header.ConnectHeaders;
import org.apache.kafka.connect.header.Header;
import org.apache.kafka.connect.header.Headers;
import org.apache.kafka.connect.source.SourceRecord;

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

    private DocumentStateExecutionSmoke() {
    }

    public static void main(final String[] args) {
        smokePublicUpsert();
        smokePublicTombstone();
        smokeProgressRecord();
        smokeMalformedRetainedRecordFailure();
        System.out.println("OK: DocumentState executed representative records on the image runtime classpath.");
    }

    private static void smokePublicUpsert() {
        final DocumentState<SourceRecord> transform = configuredTransform();
        try {
            final SourceRecord record = documentCacheRecord(
                    cacheRow(validDocumentJson(DOCUMENT_UUID)), 987L,
                    new ConnectHeaders().addString("source-header", "stripped"));

            final SourceRecord out = transform.apply(record);

            expect(TARGET_TOPIC.equals(out.topic()), "upsert topic = " + out.topic());
            expect(out.kafkaPartition() == null, "upsert partition");
            expect(out.keySchema() == Schema.STRING_SCHEMA, "upsert key schema");
            expect(DOCUMENT_UUID.equals(out.key()), "upsert key = " + out.key());
            expect(out.valueSchema() == null, "upsert value schema");
            expect(out.timestamp() == null, "upsert timestamp");
            expect(!out.headers().iterator().hasNext(), "upsert headers stripped");
            expect(record.sourcePartition().equals(out.sourcePartition()), "upsert source partition");
            expect(record.sourceOffset().equals(out.sourceOffset()), "upsert source offset");

            final Map<?, ?> value = (Map<?, ?>) out.value();
            expect(Integer.valueOf(1).equals(value.get("contractVersion")), "upsert contractVersion");
            expect(DOCUMENT_UUID.equals(value.get("documentUuid")), "upsert documentUuid");
            expect(Long.valueOf(222L).equals(value.get("contentVersion")), "upsert contentVersion");
            expect("2026-07-30T14:15:16Z".equals(value.get("lastModifiedAt")), "upsert lastModifiedAt");

            final Map<?, ?> document = (Map<?, ?>) value.get("document");
            expect(DOCUMENT_UUID.equals(document.get("id")), "upsert document.id");
            expect("222-01234567.j._.l.i".equals(document.get("_etag")), "upsert document._etag");
            expect("2026-07-30T14:15:16Z".equals(document.get("_lastModifiedDate")),
                    "upsert document._lastModifiedDate");
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
                    Schema.STRING_SCHEMA, "ignored-source-key", null, null, 321L, headers);

            final SourceRecord out = transform.apply(record);

            expect(PROGRESS_TOPIC.equals(out.topic()), "progress topic = " + out.topic());
            expect(out.keySchema() == Schema.STRING_SCHEMA, "progress key schema");
            expect("cdc-progress".equals(out.key()), "progress key = " + out.key());
            expect(out.valueSchema() == null, "progress value schema");
            expect(out.value() == null, "progress value");
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
        return relationalRecord("DocumentCache", "c", keyStruct(DOCUMENT_UUID), "after", after, timestamp, headers);
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
                .put("DocumentUuid", DOCUMENT_UUID)
                .put("ProjectName", "Ed-Fi")
                .put("ResourceName", "StudentSchoolAssociation")
                .put("ResourceVersion", "1.0")
                .put("ContentVersion", 222L)
                .put("StreamEtag", "222-01234567.j._.l.i")
                .put("LastModifiedAt", "2026-07-30T14:15:16.123456Z")
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

    private static String validDocumentJson(final String documentUuid) {
        return "{\"id\":\"" + documentUuid
                + "\",\"_lastModifiedDate\":\"2026-07-30T14:15:16Z\",\"schoolId\":255901}";
    }

    private static void expect(final boolean condition, final String detail) {
        if (!condition) {
            fail(detail);
        }
    }

    private static void fail(final String detail) {
        throw new IllegalStateException("DocumentState execution smoke failed: " + detail);
    }
}
