/*
 * Copyright 2019 Aiven Oy
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * SPDX-License-Identifier: Apache-2.0
 * This file has been modified by the Ed-Fi Alliance.
 */

package org.edfi.kafka.connect.transforms;

import java.util.Map;
import java.util.Optional;

import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.connect.connector.ConnectRecord;
import org.apache.kafka.connect.errors.DataException;
import org.apache.kafka.connect.transforms.Transformation;

// Renames DMS message topic to align with correct OpenSearch index
public class RenameDmsTopicToOpenSearchIndex<R extends ConnectRecord<R>> implements Transformation<R> {

    // Empty SMT configuration
    public static final ConfigDef CONFIG_DEF = new ConfigDef();

    public static final String PROJECT_NAME_FIELD = "projectname";
    public static final String RESOURCE_NAME_FIELD = "resourcename";
    public static final String EDFI_DOC_FIELD = "edfidoc";
    public static final char INDEX_FRAGMENT_SEPARATOR = '$';

    @Override
    public ConfigDef config() {
        // No configuration needed
        return CONFIG_DEF;
    }

    // No configuration needed
    @Override
    public void configure(final Map<String, ?> settings) {
    }

    // Change the topic to the OpenSearch index name
    @Override
    public R apply(final R record) {

        // Ignore tombstone records
        if (record.value() == null) {
            return null;
        }

        if (!(record.value() instanceof Map)) {
            throw new DataException("value type must be an object: " + record.toString());
        }

        @SuppressWarnings("unchecked")
        final Map<String, Object> recordValues = (Map<String, Object>) record.value();

        // Build the index name for the new topic
        final StringBuilder indexNameBuilder = new StringBuilder();
        indexNameBuilder.append(indexNameFragmentFromField(PROJECT_NAME_FIELD, recordValues));
        indexNameBuilder.append(INDEX_FRAGMENT_SEPARATOR);
        indexNameBuilder.append(indexNameFragmentFromField(RESOURCE_NAME_FIELD, recordValues));

        return record.newRecord(
                indexNameBuilder.toString(),
                record.kafkaPartition(),
                record.keySchema(),
                record.key(),
                record.valueSchema(),
                record.value(),
                record.timestamp(),
                record.headers());
    }

    // Returns the index name fragment from the given record field name, converting
    // any dots to dashes first
    private String indexNameFragmentFromField(final String fieldName, final Map<String, Object> recordValues) {
        return getStringField(fieldName, recordValues).replace(".", "-");
    }

    // Returns string field with the given field name
    private String getStringField(final String fieldName, final Map<String, Object> recordValues) {
        final Optional<String> result = Optional.ofNullable(recordValues.get(fieldName))
                .map(field -> {
                    if (!field.getClass().equals(String.class)) {
                        throw new DataException(fieldName + " must be a string in record: " + recordValues.toString());
                    }
                    return field;
                })
                .map(Object::toString);

        if (result.isPresent() && !result.get().isBlank()) {
            return result.get();
        } else {
            throw new DataException(fieldName + " in value can't be null or empty");
        }
    }

    @Override
    public void close() {
    }
}
