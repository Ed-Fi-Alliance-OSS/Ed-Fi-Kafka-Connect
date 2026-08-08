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
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.kafka.connect.connector.ConnectRecord;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.header.ConnectHeaders;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

final class DocumentStateJson {

    private static final String PUBLIC_DOCUMENT_ETAG_FIELD = "_etag";
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final DateTimeFormatter UTC_SECONDS_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    private DocumentStateJson() {
    }

    static <R extends ConnectRecord<R>> R publicUpsertRecord(
            final R record,
            final String targetTopic,
            final DocumentState.ValidatedDocumentKey documentKey,
            final Map<String, Object> value) {
        return record.newRecord(
                targetTopic, null, documentKey.schema(), documentKey.value(), null, value, null,
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

    static Map<String, Object> parseDocumentJson(
            final DocumentState.RetainedCacheRow row,
            final ConnectRecord<?> record,
            final DocumentState.ClassifiedRecord classifiedRecord) {
        final JsonNode node;
        try {
            node = MAPPER.readTree(row.documentJson());
        } catch (final JsonProcessingException e) {
            throw failure(DocumentState.FailureReason.INVALID_DOCUMENT_JSON, record, classifiedRecord);
        }
        if (node == null || !node.isObject()) {
            throw failure(DocumentState.FailureReason.INVALID_DOCUMENT_JSON, record, classifiedRecord);
        }

        final Map<String, Object> document = jsonObject(node, record, classifiedRecord);
        if (document.containsKey(PUBLIC_DOCUMENT_ETAG_FIELD)) {
            throw failure(DocumentState.FailureReason.DOCUMENT_JSON_HAS_ETAG, record, classifiedRecord);
        }
        document.put(PUBLIC_DOCUMENT_ETAG_FIELD, row.streamEtag());
        return document;
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

    private static Map<String, Object> jsonObject(
            final JsonNode node,
            final ConnectRecord<?> record,
            final DocumentState.ClassifiedRecord classifiedRecord) {
        final Map<String, Object> object = new LinkedHashMap<>();
        final Iterator<Map.Entry<String, JsonNode>> it = node.fields();
        while (it.hasNext()) {
            final Map.Entry<String, JsonNode> entry = it.next();
            object.put(entry.getKey(), jsonValue(entry.getValue(), record, classifiedRecord));
        }
        return object;
    }

    private static List<Object> jsonArray(
            final JsonNode node,
            final ConnectRecord<?> record,
            final DocumentState.ClassifiedRecord classifiedRecord) {
        final List<Object> array = new ArrayList<>();
        for (final JsonNode child : node) {
            array.add(jsonValue(child, record, classifiedRecord));
        }
        return array;
    }

    private static Object jsonValue(
            final JsonNode node,
            final ConnectRecord<?> record,
            final DocumentState.ClassifiedRecord classifiedRecord) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            return jsonObject(node, record, classifiedRecord);
        }
        if (node.isArray()) {
            return jsonArray(node, record, classifiedRecord);
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isNumber()) {
            return jsonNumber(node, record, classifiedRecord);
        }
        return node.asText();
    }

    private static Object jsonNumber(
            final JsonNode node,
            final ConnectRecord<?> record,
            final DocumentState.ClassifiedRecord classifiedRecord) {
        if (!node.isIntegralNumber() || !node.canConvertToLong()) {
            throw failure(DocumentState.FailureReason.INVALID_DOCUMENT_JSON, record, classifiedRecord);
        }

        return node.asLong();
    }

    private static DocumentState.TransformationFailureException failure(
            final DocumentState.FailureReason reason,
            final ConnectRecord<?> record,
            final DocumentState.ClassifiedRecord classifiedRecord) {
        return DocumentState.classifiedFailure(reason, record, classifiedRecord);
    }
}
