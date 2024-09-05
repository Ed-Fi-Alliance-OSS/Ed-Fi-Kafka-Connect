/*
 * SPDX-License-Identifier: Apache-2.0
 */

package org.edfi.kafka.connect.transforms;

import java.util.Map;

import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.connect.connector.ConnectRecord;
import org.apache.kafka.connect.data.Struct;
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

    // If _deleted=true, nulls out the record value.
    @Override
    public R apply(final R record) {

        // Ignore tombstone records
        if (record.value() == null) {
            return null;
        }

        if (!(record.value() instanceof Struct)) {
            throw new DataException("Value type must be a Struct: " + record.toString());
        }

        Boolean isDeleted = false;
        try {
            final Struct recordValueStruct = (Struct) record.value();
            isDeleted = recordValueStruct.getBoolean(DELETED_FIELD);
        } catch (final Exception e) {
            throw new DataException("Value type must have __deleted: " + record.toString());
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
