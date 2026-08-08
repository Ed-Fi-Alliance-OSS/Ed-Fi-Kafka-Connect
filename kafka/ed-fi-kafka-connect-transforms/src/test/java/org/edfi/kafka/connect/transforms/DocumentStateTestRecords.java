// SPDX-License-Identifier: Apache-2.0
// Licensed to the Ed-Fi Alliance under one or more agreements.
// The Ed-Fi Alliance licenses this file to you under the Apache License, Version 2.0.
// See the LICENSE and NOTICES files in the project root for more information.

package org.edfi.kafka.connect.transforms;

import java.util.HashMap;
import java.util.LinkedHashMap;
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

    static CacheRowBuilder cacheRowBuilder(final String provider) {
        return new CacheRowBuilder(provider);
    }

    static Schema pinnedUuidSchema(final String provider) {
        if (DocumentState.POSTGRESQL_PROVIDER.equals(provider)) {
            return SchemaBuilder.string().name(POSTGRESQL_UUID_SCHEMA).build();
        }
        return Schema.STRING_SCHEMA;
    }

    static Schema documentJsonSchema(final String provider) {
        if (DocumentState.POSTGRESQL_PROVIDER.equals(provider)) {
            return SchemaBuilder.string().name(POSTGRESQL_JSON_SCHEMA).build();
        }
        return Schema.STRING_SCHEMA;
    }

    static Schema lastModifiedAtSchema(final String provider) {
        if (DocumentState.POSTGRESQL_PROVIDER.equals(provider)) {
            return SchemaBuilder.string().name(POSTGRESQL_TIMESTAMP_SCHEMA).build();
        }
        return SchemaBuilder.string().name(SQLSERVER_TIMESTAMP_SCHEMA).build();
    }

    private static SourceRecord documentCacheRecord(
            final String provider,
            final String documentUuid,
            final Struct after,
            final Long timestamp,
            final Headers headers) {
        final Schema sourceStructSchema = sourceSchema(sourceSchemaName(provider));
        final Schema valueSchema = SchemaBuilder.struct()
                .field("source", sourceStructSchema)
                .field("op", Schema.STRING_SCHEMA)
                .field("after", after.schema())
                .build();
        final Struct value = new Struct(valueSchema)
                .put("source", source(sourceStructSchema, "dms", "DocumentCache"))
                .put("op", "c")
                .put("after", after);
        final Schema keySchema = keyStructSchema(provider);
        final Struct key = new Struct(keySchema).put(DOCUMENT_UUID_FIELD, documentUuid);
        return new SourceRecord(
                sourcePartition(), sourceOffset(), "server.dms.DocumentCache", null,
                keySchema, key, valueSchema, value, timestamp, headers);
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

        CacheRowBuilder value(final String fieldName, final Object value) {
            values.put(fieldName, value);
            return this;
        }

        Struct build() {
            final SchemaBuilder builder = SchemaBuilder.struct()
                    .name("server.dms." + sourceSchemaName(provider) + ".DocumentCache.Value");
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
