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
import org.apache.kafka.connect.header.ConnectHeaders;
import org.apache.kafka.connect.source.SourceRecord;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.edfi.kafka.connect.converters.DocumentStateJsonConverter;

final class DocumentStateJson {

    private static final DateTimeFormatter UTC_SECONDS_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);
    private static final String PUBLIC_CONTRACT_VERSION_FIELD = "contractVersion";
    private static final String PUBLIC_DOCUMENT_UUID_FIELD = "documentUuid";
    private static final String PUBLIC_PROJECT_NAME_FIELD = "projectName";
    private static final String PUBLIC_RESOURCE_NAME_FIELD = "resourceName";
    private static final String PUBLIC_RESOURCE_VERSION_FIELD = "resourceVersion";
    private static final String PUBLIC_CONTENT_VERSION_FIELD = "contentVersion";
    private static final String PUBLIC_LAST_MODIFIED_AT_FIELD = "lastModifiedAt";
    private static final String PUBLIC_DOCUMENT_FIELD = "document";
    private static final String PUBLIC_DOCUMENT_ID_FIELD = "id";
    private static final String PUBLIC_DOCUMENT_ETAG_FIELD = "_etag";
    private static final String PUBLIC_DOCUMENT_LAST_MODIFIED_DATE_FIELD = "_lastModifiedDate";
    private static final int CONTRACT_VERSION = 1;

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
            .nodeFactory(JsonNodeFactory.withExactBigDecimals(true))
            .build();

    private DocumentStateJson() {
    }

    static <R extends ConnectRecord<R>> R publicUpsertRecord(
            final R record,
            final String targetTopic,
            final DocumentState.ValidatedDocumentKey documentKey,
            final ByteBackedValue value) {
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
            final String progressKey,
            final boolean nativeHeartbeat,
            final String nativeHeartbeatProgressValue) {
        final Schema valueSchema = nativeHeartbeat && record.valueSchema() == null && record.value() == null
                ? Schema.STRING_SCHEMA : record.valueSchema();
        final Object value = nativeHeartbeat && record.valueSchema() == null && record.value() == null
                ? nativeHeartbeatProgressValue : record.value();
        return record.newRecord(
                progressTopic, null, Schema.STRING_SCHEMA, progressKey, valueSchema, value, record.timestamp(),
                record.headers());
    }

    static ByteBackedValue publicUpsertValue(
            final DocumentState.RetainedCacheRow row,
            final DocumentState.ValidatedDocumentKey documentKey,
            final ConnectRecord<?> record,
            final DocumentState.ClassifiedRecord classifiedRecord) {
        final ObjectNode documentNode = documentNode(row, record, classifiedRecord);
        validatePublicDocument(documentNode, documentKey, row, record, classifiedRecord);
        documentNode.put(PUBLIC_DOCUMENT_ETAG_FIELD, row.streamEtag());

        final ObjectNode envelope = MAPPER.createObjectNode();
        envelope.put(PUBLIC_CONTRACT_VERSION_FIELD, CONTRACT_VERSION);
        envelope.put(PUBLIC_DOCUMENT_UUID_FIELD, documentKey.value());
        envelope.put(PUBLIC_PROJECT_NAME_FIELD, row.projectName());
        envelope.put(PUBLIC_RESOURCE_NAME_FIELD, row.resourceName());
        envelope.put(PUBLIC_RESOURCE_VERSION_FIELD, row.resourceVersion());
        envelope.put(PUBLIC_CONTENT_VERSION_FIELD, row.contentVersion());
        envelope.put(PUBLIC_LAST_MODIFIED_AT_FIELD, row.lastModifiedAt());
        envelope.set(PUBLIC_DOCUMENT_FIELD, documentNode);
        return new ByteBackedValue(DocumentStateJsonConverter.publicValueSchema(),
                serializedEnvelope(envelope, record, classifiedRecord));
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

    private static ObjectNode documentNode(
            final DocumentState.RetainedCacheRow row,
            final ConnectRecord<?> record,
            final DocumentState.ClassifiedRecord classifiedRecord) {
        final JsonNode node;
        try {
            node = MAPPER.readTree(row.documentJson());
        } catch (final JsonProcessingException e) {
            throw failure(DocumentState.FailureReason.INVALID_DOCUMENT_JSON, record, classifiedRecord);
        }
        if (!(node instanceof ObjectNode)) {
            throw failure(DocumentState.FailureReason.INVALID_DOCUMENT_JSON, record, classifiedRecord);
        }

        final ObjectNode documentNode = (ObjectNode) node;
        if (documentNode.has(PUBLIC_DOCUMENT_ETAG_FIELD)) {
            throw failure(DocumentState.FailureReason.DOCUMENT_JSON_HAS_ETAG, record, classifiedRecord);
        }
        return documentNode;
    }

    private static void validatePublicDocument(
            final ObjectNode documentNode,
            final DocumentState.ValidatedDocumentKey documentKey,
            final DocumentState.RetainedCacheRow row,
            final ConnectRecord<?> record,
            final DocumentState.ClassifiedRecord classifiedRecord) {
        requireTextField(
                documentNode, PUBLIC_DOCUMENT_ID_FIELD, documentKey.value(), record, classifiedRecord);
        requireTextField(
                documentNode, PUBLIC_DOCUMENT_LAST_MODIFIED_DATE_FIELD, row.lastModifiedAt(), record,
                classifiedRecord);
    }

    private static void requireTextField(
            final ObjectNode documentNode,
            final String fieldName,
            final String expectedValue,
            final ConnectRecord<?> record,
            final DocumentState.ClassifiedRecord classifiedRecord) {
        final JsonNode value = documentNode.get(fieldName);
        if (value == null || !value.isTextual() || !expectedValue.equals(value.textValue())) {
            throw failure(
                    DocumentState.FailureReason.PUBLIC_DOCUMENT_INVARIANT_MISMATCH, record, classifiedRecord);
        }
    }

    private static byte[] serializedEnvelope(
            final ObjectNode envelope,
            final ConnectRecord<?> record,
            final DocumentState.ClassifiedRecord classifiedRecord) {
        try {
            return MAPPER.writeValueAsBytes(envelope);
        } catch (final JsonProcessingException e) {
            throw failure(DocumentState.FailureReason.INVALID_DOCUMENT_JSON, record, classifiedRecord);
        }
    }

    private static DocumentState.TransformationFailureException failure(
            final DocumentState.FailureReason reason,
            final ConnectRecord<?> record,
            final DocumentState.ClassifiedRecord classifiedRecord) {
        return DocumentState.classifiedFailure(reason, record, classifiedRecord);
    }

    static final class ByteBackedValue {
        private final Schema schema;
        private final byte[] value;

        ByteBackedValue(final Schema schema, final byte[] value) {
            this.schema = schema;
            this.value = value;
        }

        Schema schema() {
            return schema;
        }

        byte[] value() {
            return value;
        }
    }
}
