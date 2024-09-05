// SPDX-License-Identifier: Apache-2.0
// Licensed to the Ed-Fi Alliance under one or more agreements.
// The Ed-Fi Alliance licenses this file to you under the Apache License, Version 2.0.
// See the LICENSE and NOTICES files in the project root for more information.

package org.edfi.kafka.connect.transforms;

import java.util.Map;

import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.connect.connector.ConnectRecord;
import org.apache.kafka.connect.errors.DataException;
import org.apache.kafka.connect.transforms.Transformation;

public class DebeziumDeletedToTombstone<R extends ConnectRecord<R>> implements Transformation<R> {

    // Empty SMT configuration
    public static final ConfigDef CONFIG_DEF = new ConfigDef();

    public static final String DELETED_FIELD = "__deleted";

    @Override
    public ConfigDef config() {
        // No configuration needed
        return CONFIG_DEF;
    }

    // No configuration needed
    @Override
    public void configure(final Map<String, ?> settings) {
    }

    // If __deleted=true, nulls out the record value.
    @Override
    public R apply(final R record) {

        // Ignore tombstone records
        if (record.value() == null) {
            return null;
        }

        if (!(record.value() instanceof Map)) {
            throw new DataException("Value type must be castable to Map: " + record.toString());
        }

        boolean isDeleted = false;
        try {
            @SuppressWarnings("unchecked")
            final Map<String, Object> recordValueAsMap = (Map<String, Object>) record.value();
            final String isDeletedString = (String) recordValueAsMap.get(DELETED_FIELD);
            isDeleted = Boolean.valueOf(isDeletedString);
        } catch (final Exception e) {
            throw new DataException(
                    "Value type must have __deleted as String: " + record.toString() + " " + e.toString());
        }

        return record.newRecord(
                record.topic(),
                record.kafkaPartition(),
                record.keySchema(),
                record.key(),
                isDeleted ? null : record.valueSchema(),
                isDeleted ? null : record.value(),
                record.timestamp(),
                record.headers());
    }

    @Override
    public void close() {
    }
}
