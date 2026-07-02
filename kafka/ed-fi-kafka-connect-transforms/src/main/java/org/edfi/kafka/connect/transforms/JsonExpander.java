// SPDX-License-Identifier: Apache-2.0
// Licensed to the Ed-Fi Alliance under one or more agreements.
// The Ed-Fi Alliance licenses this file to you under the Apache License, Version 2.0.
// See the LICENSE and NOTICES files in the project root for more information.

package org.edfi.kafka.connect.transforms;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaAndValue;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.errors.DataException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

// Clean-room JSON expansion built on Jackson (no BSON). Parses a JSON-object string into
// structured Connect values. Inspired by the behavior of joshuagrisham/expand-json and the
// retired RedHat expandjsonsmt, but written from scratch to the Ed-Fi contract.
final class JsonExpander {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private JsonExpander() {
    }

    // Parses the string, requiring the JSON root to be an object; fail-fast otherwise.
    private static JsonNode parseObject(final String field, final String json) {
        final JsonNode node;
        try {
            node = MAPPER.readTree(json);
        } catch (final JsonProcessingException e) {
            throw new DataException("ExpandJson could not parse field '" + field + "' as JSON", e);
        }
        if (node == null || !node.isObject()) {
            throw new DataException("ExpandJson field '" + field + "' must contain a JSON object");
        }
        return node;
    }

    // Parses a configured field's JSON-object string into a schema-backed Connect value.
    static SchemaAndValue expandToStruct(final String field, final String json) {
        final JsonNode node = parseObject(field, json);
        final Schema schema = schemaOf(List.of(node));
        return new SchemaAndValue(schema, toConnectValue(node, schema));
    }

    // Infers one Connect schema describing every sample node (nulls already removed). Object and
    // array-element schemas are merged recursively across samples so that fields appearing in only
    // some samples are unioned (all optional); incompatible non-null types fail fast.
    private static Schema schemaOf(final List<JsonNode> samples) {
        if (samples.isEmpty()) {
            return Schema.OPTIONAL_STRING_SCHEMA;
        }
        if (allObjects(samples)) {
            return objectSchema(samples);
        }
        if (allArrays(samples)) {
            return SchemaBuilder.array(elementSchemaOf(samples)).optional().build();
        }
        if (allScalars(samples)) {
            return scalarSchemaOf(samples);
        }
        throw new DataException("ExpandJson cannot expand a JSON array mixing objects, arrays, and scalars");
    }

    private static Schema objectSchema(final List<JsonNode> objects) {
        final Map<String, List<JsonNode>> fieldSamples = new LinkedHashMap<>();
        for (final JsonNode object : objects) {
            final Iterator<Map.Entry<String, JsonNode>> it = object.fields();
            while (it.hasNext()) {
                final Map.Entry<String, JsonNode> entry = it.next();
                final List<JsonNode> values = fieldSamples.computeIfAbsent(entry.getKey(), key -> new ArrayList<>());
                if (!entry.getValue().isNull()) {
                    values.add(entry.getValue());
                }
            }
        }
        final SchemaBuilder builder = SchemaBuilder.struct().optional();
        fieldSamples.forEach((name, values) -> builder.field(name, schemaOf(values)));
        return builder.build();
    }

    private static Schema elementSchemaOf(final List<JsonNode> arrays) {
        final List<JsonNode> elements = new ArrayList<>();
        for (final JsonNode array : arrays) {
            for (final JsonNode element : array) {
                if (!element.isNull()) {
                    elements.add(element);
                }
            }
        }
        return schemaOf(elements);
    }

    private static Schema scalarSchemaOf(final List<JsonNode> scalars) {
        Schema merged = null;
        for (final JsonNode scalar : scalars) {
            merged = mergeScalar(merged, scalarSchema(scalar));
        }
        return merged;
    }

    // Merges the scalar schemas inferred from different samples of the same field or array element.
    // Integral and decimal numbers are compatible: a mix (e.g. [1, 2.5]) promotes to FLOAT64.
    // Any other type mismatch (e.g. number vs string) fails fast.
    private static Schema mergeScalar(final Schema merged, final Schema current) {
        if (merged == null || merged.type() == current.type()) {
            return current;
        }
        if (isNumeric(merged) && isNumeric(current)) {
            return Schema.OPTIONAL_FLOAT64_SCHEMA;
        }
        throw new DataException("ExpandJson cannot expand a JSON array with mixed scalar types: "
                + merged.type() + " and " + current.type());
    }

    private static boolean isNumeric(final Schema schema) {
        return schema.type() == Schema.Type.INT64 || schema.type() == Schema.Type.FLOAT64;
    }

    // Numeric contract: integral numbers map to INT64 (values outside the signed 64-bit range
    // fail fast in toInt64); all other numbers map to FLOAT64, rounded to the nearest IEEE 754
    // double.
    private static Schema scalarSchema(final JsonNode scalar) {
        if (scalar.isBoolean()) {
            return Schema.OPTIONAL_BOOLEAN_SCHEMA;
        }
        if (scalar.isIntegralNumber()) {
            return Schema.OPTIONAL_INT64_SCHEMA;
        }
        if (scalar.isNumber()) {
            return Schema.OPTIONAL_FLOAT64_SCHEMA;
        }
        return Schema.OPTIONAL_STRING_SCHEMA;
    }

    private static boolean allObjects(final List<JsonNode> nodes) {
        for (final JsonNode node : nodes) {
            if (!node.isObject()) {
                return false;
            }
        }
        return true;
    }

    private static boolean allArrays(final List<JsonNode> nodes) {
        for (final JsonNode node : nodes) {
            if (!node.isArray()) {
                return false;
            }
        }
        return true;
    }

    private static boolean allScalars(final List<JsonNode> nodes) {
        for (final JsonNode node : nodes) {
            if (node.isObject() || node.isArray()) {
                return false;
            }
        }
        return true;
    }

    private static Object toConnectValue(final JsonNode node, final Schema schema) {
        if (node == null || node.isNull()) {
            return null;
        }
        switch (schema.type()) {
            case STRUCT:
                return toStruct(node, schema);
            case ARRAY:
                return toArray(node, schema);
            case INT64:
                return toInt64(node);
            case FLOAT64:
                return toFloat64(node);
            case BOOLEAN:
                return node.asBoolean();
            default:
                return node.asText();
        }
    }

    // Guards the INT64 mapping: Jackson's asLong() silently wraps an integral value outside the
    // signed 64-bit range (e.g. 9223372036854775808 becomes Long.MIN_VALUE), which would corrupt
    // data. Fail fast instead.
    private static long toInt64(final JsonNode node) {
        if (!node.canConvertToLong()) {
            throw new DataException("ExpandJson cannot expand integral number " + node.asText()
                    + " because it does not fit in a Connect INT64 (signed 64-bit long)");
        }
        return node.asLong();
    }

    // Guards the FLOAT64 mapping like toInt64 guards INT64: a decimal beyond the finite double
    // range (e.g. 1e999) parses to Infinity, which is not representable in JSON and would be
    // silently type-corrupted downstream. Fail fast instead. (Rounding within the finite range
    // remains the documented contract.)
    private static double toFloat64(final JsonNode node) {
        final double value = node.asDouble();
        if (!Double.isFinite(value)) {
            throw new DataException("ExpandJson cannot expand number " + node.asText()
                    + " because it does not fit in a finite Connect FLOAT64 (IEEE 754 double)");
        }
        return value;
    }

    private static Struct toStruct(final JsonNode node, final Schema schema) {
        final Struct struct = new Struct(schema);
        for (final Field field : schema.fields()) {
            final JsonNode child = node.get(field.name());
            if (child != null && !child.isNull()) {
                struct.put(field.name(), toConnectValue(child, field.schema()));
            }
        }
        return struct;
    }

    private static List<Object> toArray(final JsonNode node, final Schema schema) {
        final List<Object> values = new ArrayList<>();
        final Schema element = schema.valueSchema();
        for (final JsonNode child : node) {
            values.add(toConnectValue(child, element));
        }
        return values;
    }
}
