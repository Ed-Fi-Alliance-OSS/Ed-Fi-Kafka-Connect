// SPDX-License-Identifier: Apache-2.0
// Licensed to the Ed-Fi Alliance under one or more agreements.
// The Ed-Fi Alliance licenses this file to you under the Apache License, Version 2.0.
// See the LICENSE and NOTICES files in the project root for more information.

package org.edfi.kafka.connect.transforms;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.header.ConnectHeaders;
import org.apache.kafka.connect.header.Headers;
import org.apache.kafka.connect.source.SourceRecord;

final class DocumentStateTestRecords {

    static final String TARGET_TOPIC = "edfi.documents";
    static final String PROGRESS_TOPIC = "edfi.documents.cdc-progress";
    static final String DOCUMENT_UUID = "aaaaaaaa-bbbb-cccc-dddd-000000000101";
    static final String OTHER_DOCUMENT_UUID = "aaaaaaaa-bbbb-cccc-dddd-000000000102";
    static final String POSTGRESQL_SOURCE_SCHEMA = "io.debezium.connector.postgresql.Source";
    static final String SQLSERVER_SOURCE_SCHEMA = "io.debezium.connector.sqlserver.Source";
    static final String POSTGRESQL_UUID_SCHEMA = "io.debezium.data.Uuid";
    static final String POSTGRESQL_JSON_SCHEMA = "io.debezium.data.Json";
    static final String POSTGRESQL_TIMESTAMP_SCHEMA = "io.debezium.time.ZonedTimestamp";
    static final String SQLSERVER_TIMESTAMP_SCHEMA = "io.debezium.time.IsoTimestamp";
    static final int DEBEZIUM_LOGICAL_SCHEMA_VERSION = 1;
    static final String DOCUMENT_UUID_FIELD = "DocumentUuid";
    static final String PROJECT_NAME_FIELD = "ProjectName";
    static final String RESOURCE_NAME_FIELD = "ResourceName";
    static final String RESOURCE_VERSION_FIELD = "ResourceVersion";
    static final String CONTENT_VERSION_FIELD = "ContentVersion";
    static final String STREAM_ETAG_FIELD = "StreamEtag";
    static final String LAST_MODIFIED_AT_FIELD = "LastModifiedAt";
    static final String DOCUMENT_JSON_FIELD = "DocumentJson";

    private DocumentStateTestRecords() {
    }

    static DocumentState<SourceRecord> configuredTransform(final String provider) {
        final DocumentState<SourceRecord> transform = new DocumentState<>();
        final Map<String, Object> config = new HashMap<>();
        config.put(DocumentState.PROVIDER_CONFIG, provider);
        config.put(DocumentState.TARGET_TOPIC_CONFIG, TARGET_TOPIC);
        config.put(DocumentState.PROGRESS_TOPIC_CONFIG, PROGRESS_TOPIC);
        transform.configure(config);
        return transform;
    }

    static SourceRecord documentCacheRecord(final String provider, final Struct after) {
        return documentCacheRecord(provider, DOCUMENT_UUID, after, null, new ConnectHeaders());
    }

    static SourceRecord documentCacheRecordWithPublicMetadata(
            final String provider, final String documentUuid, final Struct after) {
        return documentCacheRecord(
                provider, documentUuid, after, 987L, new ConnectHeaders().addString("debezium", "internal"));
    }

    static SourceRecord documentCacheDropRecord(final String provider, final String operation) {
        return relationalRecord(provider, "DocumentCache", operation, null, null, null, null,
                new ConnectHeaders());
    }

    static SourceRecord cdcHeartbeatRecord(
            final String provider,
            final String operation,
            final Schema keySchema,
            final Object key,
            final Long timestamp,
            final Headers headers) {
        return relationalRecord(provider, "CdcHeartbeat", operation, null, null, timestamp, headers, keySchema, key);
    }

    static SourceRecord nativeHeartbeatRecord(
            final String topic,
            final Schema keySchema,
            final Object key,
            final Schema valueSchema,
            final Object value,
            final Long timestamp,
            final Headers headers) {
        return new SourceRecord(
                sourcePartition(), sourceOffset(), topic, null,
                keySchema, key, valueSchema, value, timestamp, headers);
    }

    static SourceRecord documentDeleteRecord(final String provider, final Struct before) {
        final Schema beforeSchema = before == null ? null : before.schema();
        return documentDeleteRecord(provider, DOCUMENT_UUID, beforeSchema, before, null, new ConnectHeaders());
    }

    static SourceRecord documentDeleteRecordWithPublicMetadata(final String provider, final Struct before) {
        final Schema beforeSchema = before == null ? null : before.schema();
        return documentDeleteRecord(
                provider, DOCUMENT_UUID.toUpperCase(Locale.ROOT), beforeSchema, before, 987L,
                new ConnectHeaders().addString("debezium", "internal"));
    }

    static SourceRecord documentDeleteRecordWithBeforeSchema(
            final String provider,
            final Schema beforeSchema,
            final Struct before) {
        return documentDeleteRecord(provider, DOCUMENT_UUID, beforeSchema, before, null, new ConnectHeaders());
    }

    static SourceRecord automaticDebeziumDeleteTombstone(final String provider) {
        return automaticDebeziumDeleteTombstone(provider, "Document");
    }

    static SourceRecord automaticDebeziumDeleteTombstone(final String provider, final String sourceTable) {
        final Schema keySchema = keyStructSchema(provider);
        final Struct key = new Struct(keySchema).put(DOCUMENT_UUID_FIELD, DOCUMENT_UUID);
        return new SourceRecord(
                relationalTopicSourcePartition(), sourceOffset(), "server.dms." + sourceTable, null,
                keySchema, key, null, null);
    }

    static CacheRowBuilder cacheRowBuilder(final String provider) {
        return new CacheRowBuilder(provider);
    }

    static Struct documentBeforeRow(final String provider, final Object documentUuid) {
        return documentBeforeRow(documentBeforeRowSchema(provider, pinnedUuidSchema(provider)), documentUuid);
    }

    static Struct documentBeforeRow(final Schema schema, final Object documentUuid) {
        final Struct before = new Struct(schema);
        if (schema.field(DOCUMENT_UUID_FIELD) != null && documentUuid != null) {
            before.put(DOCUMENT_UUID_FIELD, documentUuid);
        }
        return before;
    }

    static Schema documentBeforeRowSchema(final String provider, final Schema documentUuidSchema) {
        return SchemaBuilder.struct()
                .name("server.dms." + sourceSchemaName(provider) + ".Document.Value")
                .optional()
                .field(DOCUMENT_UUID_FIELD, documentUuidSchema)
                .build();
    }

    static Schema documentBeforeRowSchemaWithoutDocumentUuid(final String provider) {
        return SchemaBuilder.struct()
                .name("server.dms." + sourceSchemaName(provider) + ".Document.Value")
                .optional()
                .build();
    }

    static Schema pinnedUuidSchema(final String provider) {
        if (DocumentState.POSTGRESQL_PROVIDER.equals(provider)) {
            return SchemaBuilder.string()
                    .name(POSTGRESQL_UUID_SCHEMA)
                    .version(DEBEZIUM_LOGICAL_SCHEMA_VERSION)
                    .build();
        }
        return Schema.STRING_SCHEMA;
    }

    static Schema documentJsonSchema(final String provider) {
        if (DocumentState.POSTGRESQL_PROVIDER.equals(provider)) {
            return SchemaBuilder.string()
                    .name(POSTGRESQL_JSON_SCHEMA)
                    .version(DEBEZIUM_LOGICAL_SCHEMA_VERSION)
                    .build();
        }
        return Schema.STRING_SCHEMA;
    }

    static Schema lastModifiedAtSchema(final String provider) {
        if (DocumentState.POSTGRESQL_PROVIDER.equals(provider)) {
            return SchemaBuilder.string()
                    .name(POSTGRESQL_TIMESTAMP_SCHEMA)
                    .version(DEBEZIUM_LOGICAL_SCHEMA_VERSION)
                    .build();
        }
        return SchemaBuilder.string()
                .name(SQLSERVER_TIMESTAMP_SCHEMA)
                .version(DEBEZIUM_LOGICAL_SCHEMA_VERSION)
                .build();
    }

    private static SourceRecord documentCacheRecord(
            final String provider,
            final String documentUuid,
            final Struct after,
            final Long timestamp,
            final Headers headers) {
        return relationalRecord(provider, "DocumentCache", "c", documentUuid, after.schema(), after,
                timestamp, headers);
    }

    private static SourceRecord documentDeleteRecord(
            final String provider,
            final String documentUuid,
            final Schema beforeSchema,
            final Struct before,
            final Long timestamp,
            final Headers headers) {
        return relationalRecord(provider, "Document", "d", documentUuid, beforeSchema, before, timestamp, headers);
    }

    private static SourceRecord relationalRecord(
            final String provider,
            final String sourceTable,
            final String operation,
            final String documentUuid,
            final Schema rowSchema,
            final Struct row,
            final Long timestamp,
            final Headers headers) {
        final Schema keySchema = keyStructSchema(provider);
        final Struct key = documentUuid == null
                ? null
                : new Struct(keySchema).put(DOCUMENT_UUID_FIELD, documentUuid);
        return relationalRecord(
                provider, sourceTable, operation, rowSchema, row, timestamp, headers,
                key == null ? null : keySchema, key);
    }

    private static SourceRecord relationalRecord(
            final String provider,
            final String sourceTable,
            final String operation,
            final Schema rowSchema,
            final Struct row,
            final Long timestamp,
            final Headers headers,
            final Schema keySchema,
            final Object key) {
        final Schema sourceStructSchema = sourceSchema(sourceSchemaName(provider));
        final Schema valueSchema = SchemaBuilder.struct()
                .field("source", sourceStructSchema)
                .field("op", Schema.STRING_SCHEMA)
                .build();
        final Schema valueSchemaWithRow = rowSchema == null
                ? valueSchema
                : SchemaBuilder.struct()
                        .field("source", sourceStructSchema)
                        .field("op", Schema.STRING_SCHEMA)
                        .field(rowFieldName(sourceTable, operation), rowSchema)
                        .build();
        final Struct value = new Struct(valueSchema)
                .put("source", source(sourceStructSchema, "dms", sourceTable))
                .put("op", operation);
        final Struct valueWithRow = rowSchema == null
                ? value
                : new Struct(valueSchemaWithRow)
                        .put("source", source(sourceStructSchema, "dms", sourceTable))
                        .put("op", operation);
        if (rowSchema != null && row != null) {
            valueWithRow.put(rowFieldName(sourceTable, operation), row);
        }
        return new SourceRecord(
                sourcePartition(), sourceOffset(), "server.dms." + sourceTable, null,
                keySchema, key,
                rowSchema == null ? valueSchema : valueSchemaWithRow, valueWithRow, timestamp, headers);
    }

    private static String rowFieldName(final String sourceTable, final String operation) {
        if ("Document".equals(sourceTable) && "d".equals(operation)) {
            return "before";
        }
        return "after";
    }

    private static Schema keyStructSchema(final String provider) {
        return SchemaBuilder.struct()
                .name("server.dms." + sourceSchemaName(provider) + ".Key")
                .field(DOCUMENT_UUID_FIELD, pinnedUuidSchema(provider))
                .build();
    }

    private static Schema sourceSchema(final String sourceSchemaName) {
        return SchemaBuilder.struct()
                .name(sourceSchemaName)
                .field("schema", Schema.STRING_SCHEMA)
                .field("table", Schema.STRING_SCHEMA)
                .build();
    }

    private static Struct source(final Schema sourceSchema, final String relationalSchema, final String sourceTable) {
        return new Struct(sourceSchema)
                .put("schema", relationalSchema)
                .put("table", sourceTable);
    }

    private static String sourceSchemaName(final String provider) {
        if (DocumentState.POSTGRESQL_PROVIDER.equals(provider)) {
            return POSTGRESQL_SOURCE_SCHEMA;
        }
        return SQLSERVER_SOURCE_SCHEMA;
    }

    private static Map<String, String> sourcePartition() {
        return Map.of("server", "dms");
    }

    private static Map<String, String> relationalTopicSourcePartition() {
        return Map.of("server", "server");
    }

    private static Map<String, Long> sourceOffset() {
        return Map.of("position", 1L);
    }

    static final class CacheRowBuilder {
        private final String provider;
        private final Map<String, Schema> schemas = new LinkedHashMap<>();
        private final Map<String, Object> values = new LinkedHashMap<>();

        CacheRowBuilder(final String provider) {
            this.provider = provider;
            field(DOCUMENT_UUID_FIELD, pinnedUuidSchema(provider), DOCUMENT_UUID);
            field(PROJECT_NAME_FIELD, Schema.STRING_SCHEMA, "Ed-Fi");
            field(RESOURCE_NAME_FIELD, Schema.STRING_SCHEMA, "StudentSchoolAssociation");
            field(RESOURCE_VERSION_FIELD, Schema.STRING_SCHEMA, "1.0");
            field(CONTENT_VERSION_FIELD, Schema.INT64_SCHEMA, 222L);
            field(STREAM_ETAG_FIELD, Schema.STRING_SCHEMA, "222-01234567.j._.l.i");
            field(LAST_MODIFIED_AT_FIELD, lastModifiedAtSchema(provider), "2026-07-30T14:15:16.123456Z");
            field(DOCUMENT_JSON_FIELD, documentJsonSchema(provider), defaultDocumentJson(DOCUMENT_UUID));
        }

        CacheRowBuilder field(final String fieldName, final Schema schema, final Object value) {
            schemas.put(fieldName, schema);
            values.put(fieldName, value);
            return this;
        }

        CacheRowBuilder omit(final String fieldName) {
            schemas.remove(fieldName);
            values.remove(fieldName);
            return this;
        }

        Struct build() {
            final SchemaBuilder builder = SchemaBuilder.struct()
                    .name("server.dms." + sourceSchemaName(provider) + ".DocumentCache.Value")
                    .optional();
            for (final Map.Entry<String, Schema> entry : schemas.entrySet()) {
                builder.field(entry.getKey(), entry.getValue());
            }
            final Schema schema = builder.build();
            final Struct row = new Struct(schema);
            for (final Map.Entry<String, Object> entry : values.entrySet()) {
                if (schema.field(entry.getKey()) != null && entry.getValue() != null) {
                    row.put(entry.getKey(), entry.getValue());
                }
            }
            return row;
        }

        private static String defaultDocumentJson(final String documentUuid) {
            return "{\"id\":\"" + documentUuid
                    + "\",\"_lastModifiedDate\":\"2026-07-30T14:15:16Z\",\"schoolId\":255901}";
        }
    }
}
