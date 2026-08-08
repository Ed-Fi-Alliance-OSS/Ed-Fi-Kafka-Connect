// SPDX-License-Identifier: Apache-2.0
// Licensed to the Ed-Fi Alliance under one or more agreements.
// The Ed-Fi Alliance licenses this file to you under the Apache License, Version 2.0.
// See the LICENSE and NOTICES files in the project root for more information.

package org.edfi.kafka.connect.transforms;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.errors.DataException;
import org.apache.kafka.connect.source.SourceRecord;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentStateClassificationTest {

    private static final String TOPIC = "server.dms.DocumentCache";
    private static final String POSTGRESQL_SOURCE_SCHEMA = "io.debezium.connector.postgresql.Source";
    private static final String SQLSERVER_SOURCE_SCHEMA = "io.debezium.connector.sqlserver.Source";

    @ParameterizedTest
    @MethodSource("sourceOperationClassifications")
    void Given_Relational_SourceOperation_Should_Classify(
            final String provider,
            final String sourceSchemaName,
            final String sourceTable,
            final String operation,
            final DocumentState.SourceTable expectedSourceTable,
            final DocumentState.SourceOperation expectedSourceOperation,
            final DocumentState.OutputKind expectedOutputKind) {
        final DocumentState<SourceRecord> transform = configuredTransform(provider);

        final DocumentState.ClassifiedRecord classifiedRecord =
                transform.classify(record(sourceSchemaName, "dms", sourceTable, operation));

        assertThat(classifiedRecord.sourceCategory()).isEqualTo(DocumentState.SourceCategory.RELATIONAL);
        assertThat(classifiedRecord.sourceTable()).isEqualTo(expectedSourceTable);
        assertThat(classifiedRecord.sourceOperation()).isEqualTo(expectedSourceOperation);
        assertThat(classifiedRecord.outputKind()).isEqualTo(expectedOutputKind);
    }

    @Test
    void Given_Native_Debezium_Heartbeat_With_Null_Value_Should_Classify_As_Progress() {
        final DocumentState<SourceRecord> transform = configuredTransform(DocumentState.POSTGRESQL_PROVIDER);
        final SourceRecord record = new SourceRecord(
                sourcePartition(), sourceOffset(), "__debezium-heartbeat.instance", null, null, null, null);

        final DocumentState.ClassifiedRecord classifiedRecord = transform.classify(record);

        assertThat(classifiedRecord.sourceCategory()).isEqualTo(DocumentState.SourceCategory.NATIVE_HEARTBEAT);
        assertThat(classifiedRecord.sourceTable()).isNull();
        assertThat(classifiedRecord.sourceOperation()).isNull();
        assertThat(classifiedRecord.outputKind()).isEqualTo(DocumentState.OutputKind.PROGRESS);
    }

    @Test
    void Given_Native_Debezium_Heartbeat_With_Empty_Suffix_Should_Not_Bypass_Source_Validation() {
        final DocumentState<SourceRecord> transform = configuredTransform(DocumentState.POSTGRESQL_PROVIDER);
        final SourceRecord record =
                new SourceRecord(sourcePartition(), sourceOffset(), "__debezium-heartbeat.", null, null, null, null);

        assertThatThrownBy(() -> transform.classify(record))
                .isInstanceOf(DataException.class)
                .hasMessageContaining("missing source metadata");
    }

    @ParameterizedTest
    @MethodSource("recognizedDropRecords")
    void Given_Recognized_Dropped_Operation_Should_Return_Null_Without_Public_Document_Validation(
            final String provider,
            final SourceRecord record) {
        final DocumentState<SourceRecord> transform = configuredTransform(provider);

        assertThat(transform.apply(record)).isNull();
    }

    @Test
    void Given_Missing_Source_Metadata_Should_Fail() {
        final DocumentState<SourceRecord> transform = configuredTransform(DocumentState.POSTGRESQL_PROVIDER);
        final Schema valueSchema = SchemaBuilder.struct().field("op", Schema.STRING_SCHEMA).build();
        final Struct value = new Struct(valueSchema).put("op", "c");

        assertThatThrownBy(() -> transform.classify(record(valueSchema, value)))
                .isInstanceOf(DataException.class)
                .hasMessageContaining("missing source metadata");
    }

    @Test
    void Given_Missing_Operation_Metadata_Should_Fail() {
        final DocumentState<SourceRecord> transform = configuredTransform(DocumentState.POSTGRESQL_PROVIDER);
        final Schema sourceSchema = sourceSchema(POSTGRESQL_SOURCE_SCHEMA);
        final Schema valueSchema = SchemaBuilder.struct().field("source", sourceSchema).build();
        final Struct value = new Struct(valueSchema)
                .put("source", source(sourceSchema, "dms", "DocumentCache"));

        assertThatThrownBy(() -> transform.classify(record(valueSchema, value)))
                .isInstanceOf(DataException.class)
                .hasMessageContaining("missing operation metadata");
    }

    @Test
    void Given_Unknown_Operation_Code_Should_Fail() {
        final DocumentState<SourceRecord> transform = configuredTransform(DocumentState.POSTGRESQL_PROVIDER);

        assertThatThrownBy(() -> transform.classify(record(
                        POSTGRESQL_SOURCE_SCHEMA, "dms", "DocumentCache", "x")))
                .isInstanceOf(DataException.class)
                .hasMessageContaining("unknown operation code")
                .hasMessageContaining("operation=x");
    }

    @Test
    void Given_Unsupported_Source_Schema_Should_Fail() {
        final DocumentState<SourceRecord> transform = configuredTransform(DocumentState.POSTGRESQL_PROVIDER);

        assertThatThrownBy(() -> transform.classify(record(
                        POSTGRESQL_SOURCE_SCHEMA, "public", "DocumentCache", "c")))
                .isInstanceOf(DataException.class)
                .hasMessageContaining("unsupported source schema")
                .hasMessageContaining("sourceSchema=public");
    }

    @Test
    void Given_Unsupported_Source_Table_Should_Fail() {
        final DocumentState<SourceRecord> transform = configuredTransform(DocumentState.POSTGRESQL_PROVIDER);

        assertThatThrownBy(() -> transform.classify(record(
                        POSTGRESQL_SOURCE_SCHEMA, "dms", "OtherTable", "c")))
                .isInstanceOf(DataException.class)
                .hasMessageContaining("unsupported source table")
                .hasMessageContaining("sourceTable=OtherTable");
    }

    @ParameterizedTest
    @MethodSource("providerSourceSchemas")
    void Given_DocumentProjectionWork_Source_Table_Should_Fail_Closed(
            final String provider,
            final String sourceSchemaName) {
        final DocumentState<SourceRecord> transform = configuredTransform(provider);

        assertThatThrownBy(() -> transform.classify(record(
                        sourceSchemaName, "dms", "DocumentProjectionWork", "c")))
                .isInstanceOf(DataException.class)
                .hasMessageContaining("unexpected retained source table")
                .hasMessageContaining("DocumentProjectionWork");
    }

    @Test
    void Given_Postgresql_Transform_With_SqlServer_Source_Metadata_Should_Fail() {
        final DocumentState<SourceRecord> transform = configuredTransform(DocumentState.POSTGRESQL_PROVIDER);

        assertThatThrownBy(() -> transform.classify(record(
                        SQLSERVER_SOURCE_SCHEMA, "dms", "DocumentCache", "c")))
                .isInstanceOf(DataException.class)
                .hasMessageContaining("unsupported source metadata shape");
    }

    private static Stream<Object[]> sourceOperationClassifications() {
        return Stream.concat(
                sourceOperationClassificationsFor(DocumentState.POSTGRESQL_PROVIDER, POSTGRESQL_SOURCE_SCHEMA),
                sourceOperationClassificationsFor(DocumentState.SQLSERVER_PROVIDER, SQLSERVER_SOURCE_SCHEMA));
    }

    private static Stream<Object[]> sourceOperationClassificationsFor(
            final String provider,
            final String sourceSchemaName) {
        return Stream.of(
                classification(
                        provider, sourceSchemaName, "DocumentCache", "c",
                        DocumentState.SourceTable.DOCUMENT_CACHE, DocumentState.SourceOperation.CREATE,
                        DocumentState.OutputKind.PUBLIC_UPSERT),
                classification(
                        provider, sourceSchemaName, "DocumentCache", "u",
                        DocumentState.SourceTable.DOCUMENT_CACHE, DocumentState.SourceOperation.UPDATE,
                        DocumentState.OutputKind.PUBLIC_UPSERT),
                classification(
                        provider, sourceSchemaName, "DocumentCache", "r",
                        DocumentState.SourceTable.DOCUMENT_CACHE, DocumentState.SourceOperation.READ,
                        DocumentState.OutputKind.PUBLIC_UPSERT),
                classification(
                        provider, sourceSchemaName, "DocumentCache", "d",
                        DocumentState.SourceTable.DOCUMENT_CACHE, DocumentState.SourceOperation.DELETE,
                        DocumentState.OutputKind.DROP),
                classification(
                        provider, sourceSchemaName, "DocumentCache", "t",
                        DocumentState.SourceTable.DOCUMENT_CACHE, DocumentState.SourceOperation.TRUNCATE,
                        DocumentState.OutputKind.DROP),
                classification(
                        provider, sourceSchemaName, "Document", "d",
                        DocumentState.SourceTable.DOCUMENT, DocumentState.SourceOperation.DELETE,
                        DocumentState.OutputKind.PUBLIC_TOMBSTONE),
                classification(
                        provider, sourceSchemaName, "Document", "c",
                        DocumentState.SourceTable.DOCUMENT, DocumentState.SourceOperation.CREATE,
                        DocumentState.OutputKind.DROP),
                classification(
                        provider, sourceSchemaName, "Document", "u",
                        DocumentState.SourceTable.DOCUMENT, DocumentState.SourceOperation.UPDATE,
                        DocumentState.OutputKind.DROP),
                classification(
                        provider, sourceSchemaName, "Document", "r",
                        DocumentState.SourceTable.DOCUMENT, DocumentState.SourceOperation.READ,
                        DocumentState.OutputKind.DROP),
                classification(
                        provider, sourceSchemaName, "Document", "t",
                        DocumentState.SourceTable.DOCUMENT, DocumentState.SourceOperation.TRUNCATE,
                        DocumentState.OutputKind.DROP),
                classification(
                        provider, sourceSchemaName, "CdcHeartbeat", "c",
                        DocumentState.SourceTable.HEARTBEAT, DocumentState.SourceOperation.CREATE,
                        DocumentState.OutputKind.PROGRESS),
                classification(
                        provider, sourceSchemaName, "CdcHeartbeat", "u",
                        DocumentState.SourceTable.HEARTBEAT, DocumentState.SourceOperation.UPDATE,
                        DocumentState.OutputKind.PROGRESS),
                classification(
                        provider, sourceSchemaName, "CdcHeartbeat", "r",
                        DocumentState.SourceTable.HEARTBEAT, DocumentState.SourceOperation.READ,
                        DocumentState.OutputKind.PROGRESS),
                classification(
                        provider, sourceSchemaName, "CdcHeartbeat", "d",
                        DocumentState.SourceTable.HEARTBEAT, DocumentState.SourceOperation.DELETE,
                        DocumentState.OutputKind.PROGRESS),
                classification(
                        provider, sourceSchemaName, "CdcHeartbeat", "t",
                        DocumentState.SourceTable.HEARTBEAT, DocumentState.SourceOperation.TRUNCATE,
                        DocumentState.OutputKind.PROGRESS));
    }

    private static Object[] classification(
            final String provider,
            final String sourceSchemaName,
            final String sourceTable,
            final String operation,
            final DocumentState.SourceTable expectedSourceTable,
            final DocumentState.SourceOperation expectedSourceOperation,
            final DocumentState.OutputKind expectedOutputKind) {
        return new Object[] {
            provider,
            sourceSchemaName,
            sourceTable,
            operation,
            expectedSourceTable,
            expectedSourceOperation,
            expectedOutputKind
        };
    }

    private static Stream<Object[]> recognizedDropRecords() {
        return Stream.concat(
                recognizedDropRecordsFor(DocumentState.POSTGRESQL_PROVIDER, POSTGRESQL_SOURCE_SCHEMA),
                recognizedDropRecordsFor(DocumentState.SQLSERVER_PROVIDER, SQLSERVER_SOURCE_SCHEMA));
    }

    private static Stream<Object[]> recognizedDropRecordsFor(
            final String provider,
            final String sourceSchemaName) {
        return Stream.of(
                recognizedDrop(provider, record(sourceSchemaName, "dms", "DocumentCache", "d")),
                recognizedDrop(provider, record(sourceSchemaName, "dms", "DocumentCache", "t")),
                recognizedDrop(provider, record(sourceSchemaName, "dms", "Document", "c")),
                recognizedDrop(provider, record(sourceSchemaName, "dms", "Document", "u")),
                recognizedDrop(provider, record(sourceSchemaName, "dms", "Document", "r")),
                recognizedDrop(provider, record(sourceSchemaName, "dms", "Document", "t")));
    }

    private static Object[] recognizedDrop(final String provider, final SourceRecord record) {
        return new Object[] {provider, record};
    }

    private static Stream<Object[]> providerSourceSchemas() {
        return Stream.of(
                new Object[] {DocumentState.POSTGRESQL_PROVIDER, POSTGRESQL_SOURCE_SCHEMA},
                new Object[] {DocumentState.SQLSERVER_PROVIDER, SQLSERVER_SOURCE_SCHEMA});
    }

    private static DocumentState<SourceRecord> configuredTransform(final String provider) {
        final DocumentState<SourceRecord> transform = new DocumentState<>();
        final Map<String, Object> config = new HashMap<>();
        config.put(DocumentState.PROVIDER_CONFIG, provider);
        config.put(DocumentState.TARGET_TOPIC_CONFIG, "edfi.documents");
        config.put(DocumentState.PROGRESS_TOPIC_CONFIG, "edfi.documents.cdc-progress");
        transform.configure(config);
        return transform;
    }

    private static SourceRecord record(
            final String sourceSchemaName,
            final String sourceSchema,
            final String sourceTable,
            final String operation) {
        final Schema sourceStructSchema = sourceSchema(sourceSchemaName);
        final Schema valueSchema = SchemaBuilder.struct()
                .field("source", sourceStructSchema)
                .field("op", Schema.STRING_SCHEMA)
                .build();
        final Struct value = new Struct(valueSchema)
                .put("source", source(sourceStructSchema, sourceSchema, sourceTable))
                .put("op", operation);
        return record(valueSchema, value);
    }

    private static SourceRecord record(final Schema valueSchema, final Struct value) {
        return new SourceRecord(sourcePartition(), sourceOffset(), TOPIC, null, null, null, valueSchema, value);
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

    private static Map<String, String> sourcePartition() {
        return Map.of("server", "dms");
    }

    private static Map<String, Long> sourceOffset() {
        return Map.of("position", 1L);
    }
}
