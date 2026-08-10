// SPDX-License-Identifier: Apache-2.0
// Licensed to the Ed-Fi Alliance under one or more agreements.
// The Ed-Fi Alliance licenses this file to you under the Apache License, Version 2.0.
// See the LICENSE and NOTICES files in the project root for more information.

package org.edfi.kafka.connect.converters;

import java.io.IOException;
import java.util.Arrays;
import java.util.Map;

import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaAndValue;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.errors.DataException;
import org.apache.kafka.connect.json.JsonConverter;
import org.apache.kafka.connect.storage.Converter;

public final class DocumentStateJsonConverter implements Converter {

    public static final String PUBLIC_SCHEMA_NAME = "org.edfi.kafka.connect.data.DocumentStateJson";
    public static final int PUBLIC_SCHEMA_VERSION = 1;

    private static final Schema PUBLIC_VALUE_SCHEMA = SchemaBuilder.bytes()
            .name(PUBLIC_SCHEMA_NAME)
            .version(PUBLIC_SCHEMA_VERSION)
            .build();

    private final JsonConverter delegate = new JsonConverter();

    public static Schema publicValueSchema() {
        return PUBLIC_VALUE_SCHEMA;
    }

    @Override
    public void configure(final Map<String, ?> configs, final boolean isKey) {
        delegate.configure(configs, isKey);
    }

    @Override
    public byte[] fromConnectData(final String topic, final Schema schema, final Object value) {
        if (hasReservedPublicSchemaName(schema)) {
            return publicJsonBytes(schema, value);
        }
        return delegate.fromConnectData(topic, schema, value);
    }

    @Override
    public SchemaAndValue toConnectData(final String topic, final byte[] value) {
        return delegate.toConnectData(topic, value);
    }

    @Override
    public ConfigDef config() {
        return delegate.config();
    }

    @Override
    public void close() throws IOException {
        delegate.close();
    }

    private static byte[] publicJsonBytes(final Schema schema, final Object value) {
        if (!isExactPublicValueSchema(schema) || !(value instanceof byte[])) {
            throw new DataException(
                    "Invalid DocumentState public JSON value handshake for reserved schema "
                            + PUBLIC_SCHEMA_NAME);
        }

        final byte[] bytes = (byte[]) value;
        return Arrays.copyOf(bytes, bytes.length);
    }

    private static boolean hasReservedPublicSchemaName(final Schema schema) {
        return schema != null && PUBLIC_SCHEMA_NAME.equals(schema.name());
    }

    private static boolean isExactPublicValueSchema(final Schema schema) {
        return schema.type() == Schema.Type.BYTES
                && !schema.isOptional()
                && Integer.valueOf(PUBLIC_SCHEMA_VERSION).equals(schema.version());
    }
}
