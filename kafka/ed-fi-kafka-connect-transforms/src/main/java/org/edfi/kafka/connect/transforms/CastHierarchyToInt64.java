// SPDX-License-Identifier: Apache-2.0
// Licensed to the Ed-Fi Alliance under one or more agreements.
// The Ed-Fi Alliance licenses this file to you under the Apache License, Version 2.0.
// See the LICENSE and NOTICES files in the project root for more information.

package org.edfi.kafka.connect.transforms;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.kafka.connect.connector.ConnectRecord;
import org.apache.kafka.connect.errors.DataException;
import org.apache.kafka.connect.transforms.Transformation;

public class CastHierarchyToInt64<R extends ConnectRecord<R>> implements Transformation<R> {

    private static final String FIELD_NAME = "hierarchy";

    @Override
    public R apply(final R record) {
        if (record.value() == null) {
            return record;
        }

        if (!(record.value() instanceof Map)) {
            throw new DataException("Record value is not a Map");
        }

        @SuppressWarnings("unchecked")
        final Map<String, Object> value = (Map<String, Object>) record.value();

        final Object hierarchy = value.get(FIELD_NAME);
        if (hierarchy instanceof List<?>) {
            try {
                // Convert the hierarchy to a list of Longs
                final List<Long> castedHierarchy = ((List<?>) hierarchy).stream()
                        .map(this::toLong)
                        .collect(Collectors.toList());

                // Replace the original hierarchy with the casted hierarchy
                value.put(FIELD_NAME, castedHierarchy);
            } catch (final Exception e) {
                throw new RuntimeException("Failed to cast hierarchy to int64", e);
            }
        }

        // Keep the value as a Map, but return the casted hierarchy value (list of Longs)
        return record.newRecord(
            record.topic(),
            record.kafkaPartition(),
            record.keySchema(),
            record.key(),
            record.valueSchema(),
            value,
            record.timestamp());
    }

    private Long toLong(final Object value) {
        if (value instanceof Long) {
            return (Long) value;
        } else if (value instanceof Integer) {
            return ((Integer) value).longValue();
        } else if (value instanceof Number) {
            return ((Number) value).longValue();
        } else if (value instanceof String) {
            try {
                return Long.parseLong((String) value);
            } catch (final NumberFormatException e) {
                throw new IllegalArgumentException("Cannot convert value to long: " + value, e);
            }
        }
        throw new IllegalArgumentException("Cannot convert value to long: " + value);
    }

    @Override
    public org.apache.kafka.common.config.ConfigDef config() {
        return new org.apache.kafka.common.config.ConfigDef();
    }

    @Override
    public void close() {
    }

    @Override
    public void configure(final Map<String, ?> configs) {
    }
}
