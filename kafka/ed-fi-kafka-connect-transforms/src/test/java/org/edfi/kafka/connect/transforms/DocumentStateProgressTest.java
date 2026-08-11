// SPDX-License-Identifier: Apache-2.0
// Licensed to the Ed-Fi Alliance under one or more agreements.
// The Ed-Fi Alliance licenses this file to you under the Apache License, Version 2.0.
// See the LICENSE and NOTICES files in the project root for more information.

package org.edfi.kafka.connect.transforms;

import java.util.stream.Stream;

import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.header.ConnectHeaders;
import org.apache.kafka.connect.header.Header;
import org.apache.kafka.connect.header.Headers;
import org.apache.kafka.connect.source.SourceRecord;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentStateProgressTest {

    private static final String PROGRESS_KEY = "cdc-progress";
    private static final String NATIVE_HEARTBEAT_PROGRESS_VALUE = "native-heartbeat";
    private static final long TIMESTAMP = 987L;

    @ParameterizedTest
    @MethodSource("retainedHeartbeatOperations")
    void Given_CdcHeartbeat_Operation_Should_Route_To_Progress_Topic(
            final String provider,
            final String operation) {
        final SourceRecord record = DocumentStateTestRecords.cdcHeartbeatRecord(
                provider, operation, Schema.STRING_SCHEMA, "source-key", TIMESTAMP, headers());

        final SourceRecord result = DocumentStateTestRecords.configuredTransform(provider).apply(record);

        assertProgressRecord(result, record);
    }

    @ParameterizedTest
    @MethodSource("ignoredSourceKeys")
    void Given_CdcHeartbeat_Source_Key_Shape_Should_Be_Replaced(
            final Schema keySchema,
            final Object key) {
        final SourceRecord record = DocumentStateTestRecords.cdcHeartbeatRecord(
                DocumentState.POSTGRESQL_PROVIDER, "c", keySchema, key, TIMESTAMP, headers());

        final SourceRecord result = DocumentStateTestRecords
                .configuredTransform(DocumentState.POSTGRESQL_PROVIDER)
                .apply(record);

        assertProgressRecord(result, record);
    }

    @Test
    void Given_CdcHeartbeat_With_Malformed_Public_Document_Key_Should_Not_Run_Public_Validation() {
        final Schema keySchema = SchemaBuilder.struct()
                .name("server.dms.CdcHeartbeat.Key")
                .field(DocumentStateTestRecords.DOCUMENT_UUID_FIELD, Schema.BYTES_SCHEMA)
                .build();
        final Struct key = new Struct(keySchema)
                .put(DocumentStateTestRecords.DOCUMENT_UUID_FIELD, new byte[] {1, 2, 3});
        final SourceRecord record = DocumentStateTestRecords.cdcHeartbeatRecord(
                DocumentState.POSTGRESQL_PROVIDER, "u", keySchema, key, TIMESTAMP, headers());

        final SourceRecord result = DocumentStateTestRecords
                .configuredTransform(DocumentState.POSTGRESQL_PROVIDER)
                .apply(record);

        assertProgressRecord(result, record);
    }

    @ParameterizedTest
    @MethodSource("nativeHeartbeatRecords")
    void Given_Native_Debezium_Heartbeat_Should_Route_To_Progress_Topic(final SourceRecord record) {
        final SourceRecord result = DocumentStateTestRecords
                .configuredTransform(DocumentState.SQLSERVER_PROVIDER)
                .apply(record);

        assertProgressRecord(result, record);
    }

    @Test
    void Given_Native_Debezium_Heartbeat_With_Null_Value_Should_Use_Non_Null_Progress_Marker() {
        final SourceRecord record = DocumentStateTestRecords.nativeHeartbeatRecord(
                "__debezium-heartbeat.dms", null, null, null, null, TIMESTAMP, headers());

        final SourceRecord result = DocumentStateTestRecords
                .configuredTransform(DocumentState.SQLSERVER_PROVIDER)
                .apply(record);

        assertProgressRecordWithValue(result, record, Schema.STRING_SCHEMA, NATIVE_HEARTBEAT_PROGRESS_VALUE);
    }

    @Test
    void Given_Native_Debezium_Heartbeat_With_Null_Value_And_Value_Schema_Should_Use_Non_Null_Progress_Marker() {
        final SourceRecord record = DocumentStateTestRecords.nativeHeartbeatRecord(
                "__debezium-heartbeat.dms", null, null, Schema.STRING_SCHEMA, null, TIMESTAMP, headers());

        final SourceRecord result = DocumentStateTestRecords
                .configuredTransform(DocumentState.SQLSERVER_PROVIDER)
                .apply(record);

        assertProgressRecordWithValue(result, record, Schema.STRING_SCHEMA, NATIVE_HEARTBEAT_PROGRESS_VALUE);
    }

    private static Stream<Object[]> retainedHeartbeatOperations() {
        return Stream.of(
                new Object[] {DocumentState.POSTGRESQL_PROVIDER, "c"},
                new Object[] {DocumentState.POSTGRESQL_PROVIDER, "u"},
                new Object[] {DocumentState.POSTGRESQL_PROVIDER, "r"},
                new Object[] {DocumentState.POSTGRESQL_PROVIDER, "d"},
                new Object[] {DocumentState.POSTGRESQL_PROVIDER, "t"},
                new Object[] {DocumentState.SQLSERVER_PROVIDER, "c"},
                new Object[] {DocumentState.SQLSERVER_PROVIDER, "u"},
                new Object[] {DocumentState.SQLSERVER_PROVIDER, "r"},
                new Object[] {DocumentState.SQLSERVER_PROVIDER, "d"},
                new Object[] {DocumentState.SQLSERVER_PROVIDER, "t"});
    }

    private static Stream<Object[]> ignoredSourceKeys() {
        return Stream.of(
                schemaBackedHeartbeatKey(),
                new Object[] {Schema.STRING_SCHEMA, "scalar-source-key"},
                new Object[] {null, null});
    }

    private static Object[] schemaBackedHeartbeatKey() {
        final Schema keySchema = SchemaBuilder.struct()
                .name("server.dms.CdcHeartbeat.Key")
                .field("sequence", Schema.INT64_SCHEMA)
                .build();
        final Struct key = new Struct(keySchema).put("sequence", 22L);
        return new Object[] {keySchema, key};
    }

    private static Stream<SourceRecord> nativeHeartbeatRecords() {
        final Schema heartbeatValueSchema = SchemaBuilder.struct()
                .name("io.debezium.connector.common.Heartbeat")
                .field("ts_ms", Schema.INT64_SCHEMA)
                .build();
        final Struct heartbeatValue = new Struct(heartbeatValueSchema).put("ts_ms", 456L);
        final Object[] schemaBackedKey = schemaBackedHeartbeatKey();

        return Stream.of(
                DocumentStateTestRecords.nativeHeartbeatRecord(
                        "__debezium-heartbeat.dms", Schema.STRING_SCHEMA, "source-key",
                        heartbeatValueSchema, heartbeatValue, TIMESTAMP, headers()),
                DocumentStateTestRecords.nativeHeartbeatRecord(
                        "__debezium-heartbeat.dms", (Schema) schemaBackedKey[0],
                        schemaBackedKey[1], heartbeatValueSchema, heartbeatValue, TIMESTAMP, headers()));
    }

    private static Headers headers() {
        return new ConnectHeaders().addString("source-header", "kept");
    }

    private static void assertProgressRecord(final SourceRecord result, final SourceRecord record) {
        assertProgressRecordMetadata(result, record);
        assertThat(result.valueSchema()).isSameAs(record.valueSchema());
        assertThat(result.value()).isSameAs(record.value());
    }

    private static void assertProgressRecordWithValue(
            final SourceRecord result,
            final SourceRecord record,
            final Schema expectedValueSchema,
            final Object expectedValue) {
        assertProgressRecordMetadata(result, record);
        assertThat(result.valueSchema()).isSameAs(expectedValueSchema);
        assertThat(result.value()).isEqualTo(expectedValue);
    }

    private static void assertProgressRecordMetadata(final SourceRecord result, final SourceRecord record) {
        assertThat(result).isNotNull();
        assertThat(result.topic()).isEqualTo(DocumentStateTestRecords.PROGRESS_TOPIC);
        assertThat(result.kafkaPartition()).isNull();
        assertThat(result.keySchema()).isSameAs(Schema.STRING_SCHEMA);
        assertThat(result.key()).isEqualTo(PROGRESS_KEY);
        final Header sourceHeader = result.headers().lastWithName("source-header");
        assertThat(sourceHeader).isNotNull();
        assertThat(sourceHeader.key()).isEqualTo("source-header");
        assertThat(sourceHeader.schema()).isSameAs(Schema.STRING_SCHEMA);
        assertThat(sourceHeader.value()).isEqualTo("kept");
        assertThat(result.timestamp()).isEqualTo(record.timestamp());
        assertThat(result.sourcePartition()).isEqualTo(record.sourcePartition());
        assertThat(result.sourceOffset()).isEqualTo(record.sourceOffset());
    }
}
