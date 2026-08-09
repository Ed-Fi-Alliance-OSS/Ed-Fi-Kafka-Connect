// SPDX-License-Identifier: Apache-2.0
// Licensed to the Ed-Fi Alliance under one or more agreements.
// The Ed-Fi Alliance licenses this file to you under the Apache License, Version 2.0.
// See the LICENSE and NOTICES files in the project root for more information.

package org.edfi.kafka.connect.transforms;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import org.apache.kafka.connect.connector.ConnectRecord;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.header.ConnectHeaders;
import org.apache.kafka.connect.source.SourceRecord;

final class DocumentStateJson {

    private static final DateTimeFormatter UTC_SECONDS_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    private DocumentStateJson() {
    }

    static <R extends ConnectRecord<R>> R publicUpsertRecord(
            final R record,
            final String targetTopic,
            final DocumentState.ValidatedDocumentKey documentKey,
            final SchemaBackedValue value) {
        return record.newRecord(
                targetTopic, null, documentKey.schema(), documentKey.value(), value.schema(), value.value(), null,
                new ConnectHeaders());
    }

    static <R extends ConnectRecord<R>> R publicTombstoneRecord(
            final R record,
            final String targetTopic,
            final DocumentState.ValidatedDocumentKey documentKey) {
        return record.newRecord(
                targetTopic, null, documentKey.schema(), documentKey.value(), null, null, null,
                new ConnectHeaders());
    }

    static <R extends ConnectRecord<R>> R progressRecord(
            final R record,
            final String progressTopic,
            final String progressKey) {
        return record.newRecord(
                progressTopic, null, Schema.STRING_SCHEMA, progressKey, record.valueSchema(), record.value(),
                record.timestamp(), record.headers());
    }

    static SchemaBackedValue publicUpsertValue(
            final DocumentState.RetainedCacheRow row,
            final DocumentState.ValidatedDocumentKey documentKey,
            final ConnectRecord<?> record,
            final DocumentState.ClassifiedRecord classifiedRecord) {
        return DocumentStateJsonValueBuilder.publicUpsertValue(row, documentKey, record, classifiedRecord);
    }

    static String normalizeLastModifiedAt(
            final String value,
            final ConnectRecord<?> record,
            final DocumentState.ClassifiedRecord classifiedRecord) {
        final OffsetDateTime timestamp;
        try {
            timestamp = OffsetDateTime.parse(value);
        } catch (final DateTimeParseException e) {
            throw failure(DocumentState.FailureReason.INVALID_LAST_MODIFIED_AT, record, classifiedRecord);
        }
        if (!ZoneOffset.UTC.equals(timestamp.getOffset())) {
            throw failure(DocumentState.FailureReason.INVALID_LAST_MODIFIED_AT, record, classifiedRecord);
        }

        final Instant wholeSecondInstant = timestamp.toInstant().truncatedTo(ChronoUnit.SECONDS);
        return UTC_SECONDS_FORMATTER.format(wholeSecondInstant);
    }

    static String sourcePartitionServer(final ConnectRecord<?> record) {
        if (!(record instanceof SourceRecord)) {
            return null;
        }

        final Map<String, ?> sourcePartition = ((SourceRecord) record).sourcePartition();
        if (sourcePartition == null) {
            return null;
        }

        final Object sourceServer = sourcePartition.get("server");
        if (!(sourceServer instanceof String)) {
            return null;
        }
        return (String) sourceServer;
    }

    private static DocumentState.TransformationFailureException failure(
            final DocumentState.FailureReason reason,
            final ConnectRecord<?> record,
            final DocumentState.ClassifiedRecord classifiedRecord) {
        return DocumentState.classifiedFailure(reason, record, classifiedRecord);
    }

    static final class SchemaBackedValue {
        private final Schema schema;
        private final Struct value;

        SchemaBackedValue(final Schema schema, final Struct value) {
            this.schema = schema;
            this.value = value;
        }

        Schema schema() {
            return schema;
        }

        Struct value() {
            return value;
        }
    }
}
