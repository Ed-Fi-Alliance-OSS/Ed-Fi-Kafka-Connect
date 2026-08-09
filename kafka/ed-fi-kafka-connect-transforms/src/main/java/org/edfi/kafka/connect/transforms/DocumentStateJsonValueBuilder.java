// SPDX-License-Identifier: Apache-2.0
// Licensed to the Ed-Fi Alliance under one or more agreements.
// The Ed-Fi Alliance licenses this file to you under the Apache License, Version 2.0.
// See the LICENSE and NOTICES files in the project root for more information.

package org.edfi.kafka.connect.transforms;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.kafka.connect.connector.ConnectRecord;
import org.apache.kafka.connect.data.Decimal;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

final class DocumentStateJsonValueBuilder {

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

    private DocumentStateJsonValueBuilder() {
    }

    static DocumentStateJson.SchemaBackedValue publicUpsertValue(
            final DocumentState.RetainedCacheRow row,
            final DocumentState.ValidatedDocumentKey documentKey,
            final ConnectRecord<?> record,
            final DocumentState.ClassifiedRecord classifiedRecord) {
        final ObjectNode documentNode = documentNode(row, record, classifiedRecord);
        validatePublicDocument(documentNode, documentKey, row, record, classifiedRecord);
        documentNode.put(PUBLIC_DOCUMENT_ETAG_FIELD, row.streamEtag());

        final InferredValues documentValues = inferValues(List.of(documentNode), record, classifiedRecord);
        final Schema valueSchema = publicEnvelopeSchema(documentValues.schema());
        final Struct value = new Struct(valueSchema)
                .put(PUBLIC_CONTRACT_VERSION_FIELD, CONTRACT_VERSION)
                .put(PUBLIC_DOCUMENT_UUID_FIELD, documentKey.value())
                .put(PUBLIC_PROJECT_NAME_FIELD, row.projectName())
                .put(PUBLIC_RESOURCE_NAME_FIELD, row.resourceName())
                .put(PUBLIC_RESOURCE_VERSION_FIELD, row.resourceVersion())
                .put(PUBLIC_CONTENT_VERSION_FIELD, row.contentVersion())
                .put(PUBLIC_LAST_MODIFIED_AT_FIELD, row.lastModifiedAt())
                .put(PUBLIC_DOCUMENT_FIELD, documentValues.value(0));
        return new DocumentStateJson.SchemaBackedValue(valueSchema, value);
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
        if (!documentKey.value().equals(row.documentUuid())) {
            throw failure(DocumentState.FailureReason.DOCUMENT_UUID_MISMATCH, record, classifiedRecord);
        }
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

    private static Schema publicEnvelopeSchema(final Schema documentSchema) {
        return SchemaBuilder.struct()
                .field(PUBLIC_CONTRACT_VERSION_FIELD, Schema.INT32_SCHEMA)
                .field(PUBLIC_DOCUMENT_UUID_FIELD, Schema.STRING_SCHEMA)
                .field(PUBLIC_PROJECT_NAME_FIELD, Schema.STRING_SCHEMA)
                .field(PUBLIC_RESOURCE_NAME_FIELD, Schema.STRING_SCHEMA)
                .field(PUBLIC_RESOURCE_VERSION_FIELD, Schema.STRING_SCHEMA)
                .field(PUBLIC_CONTENT_VERSION_FIELD, Schema.INT64_SCHEMA)
                .field(PUBLIC_LAST_MODIFIED_AT_FIELD, Schema.STRING_SCHEMA)
                .field(PUBLIC_DOCUMENT_FIELD, documentSchema)
                .build();
    }

    private static InferredValues inferValues(
            final List<JsonNode> nodes,
            final ConnectRecord<?> record,
            final DocumentState.ClassifiedRecord classifiedRecord) {
        final JsonCategory category = commonCategory(nodes, record, classifiedRecord);
        if (category == null) {
            return nullValues(nodes.size());
        }

        switch (category) {
            case OBJECT:
                return objectValues(nodes, record, classifiedRecord);
            case ARRAY:
                return arrayValues(nodes, record, classifiedRecord);
            case NUMBER:
                return numberValues(nodes, record, classifiedRecord);
            case BOOLEAN:
                return booleanValues(nodes);
            case STRING:
                return stringValues(nodes);
            default:
                throw failure(DocumentState.FailureReason.INVALID_DOCUMENT_JSON, record, classifiedRecord);
        }
    }

    private static JsonCategory commonCategory(
            final List<JsonNode> nodes,
            final ConnectRecord<?> record,
            final DocumentState.ClassifiedRecord classifiedRecord) {
        JsonCategory category = null;
        for (final JsonNode node : nodes) {
            if (isNull(node)) {
                continue;
            }
            final JsonCategory nodeCategory = category(node, record, classifiedRecord);
            if (category == null) {
                category = nodeCategory;
            } else if (category != nodeCategory) {
                throw failure(DocumentState.FailureReason.INVALID_DOCUMENT_JSON, record, classifiedRecord);
            }
        }
        return category;
    }

    private static JsonCategory category(
            final JsonNode node,
            final ConnectRecord<?> record,
            final DocumentState.ClassifiedRecord classifiedRecord) {
        if (node.isObject()) {
            return JsonCategory.OBJECT;
        }
        if (node.isArray()) {
            return JsonCategory.ARRAY;
        }
        if (node.isNumber()) {
            return JsonCategory.NUMBER;
        }
        if (node.isBoolean()) {
            return JsonCategory.BOOLEAN;
        }
        if (node.isTextual()) {
            return JsonCategory.STRING;
        }
        throw failure(DocumentState.FailureReason.INVALID_DOCUMENT_JSON, record, classifiedRecord);
    }

    private static InferredValues objectValues(
            final List<JsonNode> nodes,
            final ConnectRecord<?> record,
            final DocumentState.ClassifiedRecord classifiedRecord) {
        final LinkedHashMap<String, List<JsonNode>> fieldNodes = objectFieldNodes(nodes);
        final LinkedHashMap<String, InferredValues> fields = new LinkedHashMap<>();
        final SchemaBuilder schemaBuilder = SchemaBuilder.struct().optional();
        for (final Map.Entry<String, List<JsonNode>> field : fieldNodes.entrySet()) {
            final InferredValues fieldValues = inferValues(field.getValue(), record, classifiedRecord);
            schemaBuilder.field(field.getKey(), fieldValues.schema());
            fields.put(field.getKey(), fieldValues);
        }

        final Schema schema = schemaBuilder.build();
        final List<Object> values = new ArrayList<>(nodes.size());
        for (int index = 0; index < nodes.size(); index++) {
            values.add(objectValue(nodes.get(index), schema, fields, index));
        }
        return new InferredValues(schema, values);
    }

    private static LinkedHashMap<String, List<JsonNode>> objectFieldNodes(final List<JsonNode> nodes) {
        final LinkedHashMap<String, List<JsonNode>> fieldNodes = new LinkedHashMap<>();
        for (int index = 0; index < nodes.size(); index++) {
            final JsonNode node = nodes.get(index);
            if (isNull(node)) {
                continue;
            }
            final Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                final Map.Entry<String, JsonNode> field = it.next();
                List<JsonNode> values = fieldNodes.get(field.getKey());
                if (values == null) {
                    values = nullNodeList(nodes.size());
                    fieldNodes.put(field.getKey(), values);
                }
                values.set(index, field.getValue());
            }
        }
        return fieldNodes;
    }

    private static Object objectValue(
            final JsonNode node,
            final Schema schema,
            final LinkedHashMap<String, InferredValues> fields,
            final int index) {
        if (isNull(node)) {
            return null;
        }

        final Struct struct = new Struct(schema);
        for (final Map.Entry<String, InferredValues> field : fields.entrySet()) {
            struct.put(field.getKey(), field.getValue().value(index));
        }
        return struct;
    }

    private static InferredValues arrayValues(
            final List<JsonNode> nodes,
            final ConnectRecord<?> record,
            final DocumentState.ClassifiedRecord classifiedRecord) {
        final List<JsonNode> elementNodes = arrayElementNodes(nodes);
        final InferredValues elements = inferValues(elementNodes, record, classifiedRecord);
        final Schema schema = SchemaBuilder.array(elements.schema()).optional().build();
        final List<Object> values = new ArrayList<>(nodes.size());
        int elementIndex = 0;
        for (final JsonNode node : nodes) {
            if (isNull(node)) {
                values.add(null);
            } else {
                final List<Object> array = new ArrayList<>(node.size());
                for (final JsonNode ignored : node) {
                    array.add(elements.value(elementIndex));
                    elementIndex++;
                }
                values.add(array);
            }
        }
        return new InferredValues(schema, values);
    }

    private static List<JsonNode> arrayElementNodes(final List<JsonNode> nodes) {
        final List<JsonNode> elements = new ArrayList<>();
        for (final JsonNode node : nodes) {
            if (isNull(node)) {
                continue;
            }
            for (final JsonNode element : node) {
                elements.add(element);
            }
        }
        return elements;
    }

    private static InferredValues numberValues(
            final List<JsonNode> nodes,
            final ConnectRecord<?> record,
            final DocumentState.ClassifiedRecord classifiedRecord) {
        if (allNumbersFitInt64(nodes)) {
            return int64Values(nodes);
        }

        final int scale = commonDecimalScale(nodes);
        final Schema schema = Decimal.builder(scale).optional().build();
        final List<Object> values = new ArrayList<>(nodes.size());
        for (final JsonNode node : nodes) {
            values.add(isNull(node) ? null : node.decimalValue());
        }
        return new InferredValues(schema, values);
    }

    private static boolean allNumbersFitInt64(final List<JsonNode> nodes) {
        for (final JsonNode node : nodes) {
            if (isNull(node)) {
                continue;
            }
            if (!node.isIntegralNumber() || !node.canConvertToLong()) {
                return false;
            }
        }
        return true;
    }

    private static InferredValues int64Values(final List<JsonNode> nodes) {
        final List<Object> values = new ArrayList<>(nodes.size());
        for (final JsonNode node : nodes) {
            values.add(isNull(node) ? null : Long.valueOf(node.longValue()));
        }
        return new InferredValues(Schema.OPTIONAL_INT64_SCHEMA, values);
    }

    private static int commonDecimalScale(final List<JsonNode> nodes) {
        int scale = 0;
        for (final JsonNode node : nodes) {
            if (!isNull(node)) {
                scale = Math.max(scale, normalizedScale(node.decimalValue()));
            }
        }
        return scale;
    }

    private static int normalizedScale(final BigDecimal value) {
        return Math.max(0, value.scale());
    }

    private static InferredValues booleanValues(final List<JsonNode> nodes) {
        final List<Object> values = new ArrayList<>(nodes.size());
        for (final JsonNode node : nodes) {
            values.add(isNull(node) ? null : Boolean.valueOf(node.booleanValue()));
        }
        return new InferredValues(Schema.OPTIONAL_BOOLEAN_SCHEMA, values);
    }

    private static InferredValues stringValues(final List<JsonNode> nodes) {
        final List<Object> values = new ArrayList<>(nodes.size());
        for (final JsonNode node : nodes) {
            values.add(isNull(node) ? null : node.textValue());
        }
        return new InferredValues(Schema.OPTIONAL_STRING_SCHEMA, values);
    }

    private static InferredValues nullValues(final int count) {
        final List<Object> values = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            values.add(null);
        }
        return new InferredValues(Schema.OPTIONAL_STRING_SCHEMA, values);
    }

    private static List<JsonNode> nullNodeList(final int count) {
        final List<JsonNode> nodes = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            nodes.add(NullNode.getInstance());
        }
        return nodes;
    }

    private static boolean isNull(final JsonNode node) {
        return node == null || node.isNull();
    }

    private static DocumentState.TransformationFailureException failure(
            final DocumentState.FailureReason reason,
            final ConnectRecord<?> record,
            final DocumentState.ClassifiedRecord classifiedRecord) {
        return DocumentState.classifiedFailure(reason, record, classifiedRecord);
    }

    private enum JsonCategory {
        OBJECT,
        ARRAY,
        NUMBER,
        BOOLEAN,
        STRING
    }

    private static final class InferredValues {
        private final Schema schema;
        private final List<Object> values;

        InferredValues(final Schema schema, final List<Object> values) {
            this.schema = schema;
            this.values = values;
        }

        Schema schema() {
            return schema;
        }

        Object value(final int index) {
            return values.get(index);
        }
    }
}
