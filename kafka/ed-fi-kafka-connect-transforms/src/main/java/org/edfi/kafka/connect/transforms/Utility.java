/*
 * SPDX-License-Identifier: Apache-2.0
 */

package org.edfi.kafka.connect.transforms;

import java.util.Map;
import java.util.Optional;

import org.apache.kafka.connect.errors.DataException;

public class Utility {
    // Returns string field with the given field name
    public static String getStringField(final String fieldName, final Map<String, Object> recordValues) {
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
}
