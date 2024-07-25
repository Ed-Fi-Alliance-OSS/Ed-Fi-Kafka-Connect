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

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GenerateIndexFromDmsResourceConfigTest {

    @Test
    void emptyFieldName() {
        final Map<String, String> props = new HashMap<>();
        props.put("field.name", "");
        final GenerateIndexFromDmsResourceConfig config = new GenerateIndexFromDmsResourceConfig(props);
        assertThat(config.fieldName()).isNotPresent();
    }

    @Test
    void definedFieldName() {
        final Map<String, String> props = new HashMap<>();
        props.put("field.name", "test");
        final GenerateIndexFromDmsResourceConfig config = new GenerateIndexFromDmsResourceConfig(props);
        assertThat(config.fieldName()).hasValue("test");
    }
}
