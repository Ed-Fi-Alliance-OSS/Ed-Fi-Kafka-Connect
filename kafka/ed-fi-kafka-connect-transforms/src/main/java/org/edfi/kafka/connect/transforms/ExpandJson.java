// SPDX-License-Identifier: Apache-2.0
// Licensed to the Ed-Fi Alliance under one or more agreements.
// The Ed-Fi Alliance licenses this file to you under the Apache License, Version 2.0.
// See the LICENSE and NOTICES files in the project root for more information.

package org.edfi.kafka.connect.transforms;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.kafka.common.config.AbstractConfig;
import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.common.config.ConfigException;
import org.apache.kafka.connect.connector.ConnectRecord;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaAndValue;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.errors.DataException;
import org.apache.kafka.connect.transforms.Transformation;

// Expands configured top-level string fields whose value is a JSON object into a structured
// Connect value. Operates on schema-backed (Struct) value records only, per the DMS-1240 design
// contract. Generic: it knows nothing about any specific table or column.
public abstract class ExpandJson<R extends ConnectRecord<R>> implements Transformation<R> {

    public static final String SOURCE_FIELDS_CONFIG = "sourceFields";

    // Rejects a missing or empty list, and any blank entry (e.g. "a,,b"), so a misconfigured
    // sourceFields fails fast at configure time instead of silently passing records through
    // unchanged.
    private static final ConfigDef.Validator NON_BLANK_FIELD_LIST = (name, value) -> {
        if (!(value instanceof List) || ((List<?>) value).isEmpty()) {
            throw new ConfigException(name, value, "must list at least one field to expand");
        }
        for (final Object field : (List<?>) value) {
            if (!(field instanceof String) || ((String) field).trim().isEmpty()) {
                throw new ConfigException(name, value, "must not contain blank field names");
            }
        }
    };

    public static final ConfigDef CONFIG_DEF = new ConfigDef()
            .define(SOURCE_FIELDS_CONFIG, ConfigDef.Type.LIST, ConfigDef.NO_DEFAULT_VALUE,
                    NON_BLANK_FIELD_LIST, ConfigDef.Importance.HIGH,
                    "Top-level string fields whose JSON-object value is expanded into a structured value.");

    private List<String> sourceFields;

    @Override
    public ConfigDef config() {
        return CONFIG_DEF;
    }

    @Override
    public void configure(final Map<String, ?> configs) {
        // Trim entries: ConfigDef's comma splitting already strips whitespace around string-config
        // entries, but a List passed programmatically bypasses that split.
        final List<String> configured = new AbstractConfig(CONFIG_DEF, configs).getList(SOURCE_FIELDS_CONFIG);
        final List<String> trimmed = new ArrayList<>(configured.size());
        for (final String field : configured) {
            trimmed.add(field.trim());
        }
        this.sourceFields = trimmed;
    }

    @Override
    public R apply(final R record) {
        final Object value = operatingValue(record);
        if (value == null) {
            // Tombstones and other null-value records pass through untouched.
            return record;
        }
        final Schema schema = operatingSchema(record);
        if (schema == null) {
            // The design contract (DMS-1240) is a schema-backed value record from the Debezium
            // source pipeline; a value without a schema means the transform is deployed against
            // the wrong converter configuration, so fail fast instead of guessing.
            throw new DataException(
                    "ExpandJson requires a schema-backed value record, but this record carries a value "
                            + "without a value schema");
        }
        return applyWithSchema(record, schema, value);
    }

    @Override
    public void close() {
    }

    private R applyWithSchema(final R record, final Schema schema, final Object value) {
        if (!(value instanceof Struct)) {
            throw new DataException(
                    "ExpandJson requires a Struct value when the record has a schema, but found: "
                            + value.getClass().getName());
        }
        final Struct original = (Struct) value;
        final Map<String, SchemaAndValue> expansions = new LinkedHashMap<>();
        for (final String field : sourceFields) {
            final Field existing = schema.field(field);
            if (existing == null) {
                continue;
            }
            requireStringSchema(field, existing);
            // getWithoutDefault: Struct.get substitutes the field schema's default for a null
            // value (e.g. a column DEFAULT propagated by Debezium), which would wrongly expand
            // a null field instead of leaving it unchanged.
            final Object fieldValue = original.getWithoutDefault(field);
            if (fieldValue == null) {
                continue;
            }
            expansions.put(field, JsonExpander.expandToStruct(field, requireStringValue(field, fieldValue)));
        }
        if (expansions.isEmpty()) {
            return record;
        }
        final Schema updatedSchema = rebuildSchema(schema, expansions);
        return newRecord(record, updatedSchema, rebuildStruct(original, updatedSchema, expansions));
    }

    private static void requireStringSchema(final String field, final Field existing) {
        if (existing.schema().type() != Schema.Type.STRING) {
            throw new DataException("ExpandJson field '" + field + "' must be a STRING, but was: "
                    + existing.schema().type());
        }
    }

    private static String requireStringValue(final String field, final Object fieldValue) {
        if (!(fieldValue instanceof String)) {
            throw new DataException("ExpandJson field '" + field + "' must be a STRING, but was: "
                    + fieldValue.getClass().getName());
        }
        return (String) fieldValue;
    }

    private static Schema rebuildSchema(final Schema original, final Map<String, SchemaAndValue> expansions) {
        final SchemaBuilder builder = SchemaBuilder.struct();
        if (original.name() != null) {
            builder.name(original.name());
        }
        if (original.version() != null) {
            builder.version(original.version());
        }
        if (original.doc() != null) {
            builder.doc(original.doc());
        }
        if (original.parameters() != null) {
            builder.parameters(original.parameters());
        }
        if (original.isOptional()) {
            builder.optional();
        }
        for (final Field field : original.fields()) {
            final SchemaAndValue expanded = expansions.get(field.name());
            builder.field(field.name(), expanded == null ? field.schema() : expanded.schema());
        }
        // A struct-level default is bound to the original field schemas, so it cannot be carried
        // onto the rebuilt schema once an expanded field changes type (STRING -> STRUCT/ARRAY):
        // Connect would reject it with a SchemaBuilderException. Debezium value schemas carry no
        // such default, so it is intentionally not copied rather than re-derived per field.
        return builder.build();
    }

    private static Struct rebuildStruct(final Struct original, final Schema updatedSchema,
            final Map<String, SchemaAndValue> expansions) {
        final Struct updated = new Struct(updatedSchema);
        for (final Field field : updatedSchema.fields()) {
            final SchemaAndValue expanded = expansions.get(field.name());
            final Object fieldValue = expanded == null ? original.getWithoutDefault(field.name()) : expanded.value();
            if (fieldValue != null) {
                updated.put(field.name(), fieldValue);
            }
        }
        return updated;
    }

    protected abstract Schema operatingSchema(R record);

    protected abstract Object operatingValue(R record);

    protected abstract R newRecord(R record, Schema updatedSchema, Object updatedValue);

    // Operates on the value of the record.
    public static class Value<R extends ConnectRecord<R>> extends ExpandJson<R> {

        @Override
        protected Schema operatingSchema(final R record) {
            return record.valueSchema();
        }

        @Override
        protected Object operatingValue(final R record) {
            return record.value();
        }

        @Override
        protected R newRecord(final R record, final Schema updatedSchema, final Object updatedValue) {
            return record.newRecord(record.topic(), record.kafkaPartition(), record.keySchema(), record.key(),
                    updatedSchema, updatedValue, record.timestamp(), record.headers());
        }
    }
}
