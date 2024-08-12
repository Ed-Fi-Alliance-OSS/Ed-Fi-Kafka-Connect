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

import org.apache.kafka.common.record.TimestampType;
import org.apache.kafka.connect.errors.DataException;
import org.apache.kafka.connect.sink.SinkRecord;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RenameDmsTopicToOpenSearchIndexTest {

    @Test
    void Given_Record_With_Null_Value_Should_Return_Null() {
        final SinkRecord result = newTransformation().apply(newRecord(null));
        assertThat(result).isEqualTo(null);
    }

    @Test
    void Given_Record_With_Non_Map_Value_Should_Throw_Exception() {
        assertThatThrownBy(() -> newTransformation().apply(newRecord(new Object())))
                .isInstanceOf(DataException.class)
                .hasMessageStartingWith("value type must be an object: ");

    }

    @Test
    void Given_Record_Without_ProjectName_Should_Throw_Exception() {
        final Map<String, Object> recordValues = new HashMap<>();
        recordValues.put(RenameDmsTopicToOpenSearchIndex.RESOURCE_NAME_FIELD, "value");
        recordValues.put(RenameDmsTopicToOpenSearchIndex.EDFI_DOC_FIELD, "value");
        assertThatThrownBy(() -> newTransformation().apply(newRecord(recordValues)))
                .isInstanceOf(DataException.class)
                .hasMessageStartingWith("projectname in value can't be null or empty");
    }

    @Test
    void Given_Record_Without_ResourceName_Should_Throw_Exception() {
        final Map<String, Object> recordValues = new HashMap<>();
        recordValues.put(RenameDmsTopicToOpenSearchIndex.PROJECT_NAME_FIELD, "value");
        recordValues.put(RenameDmsTopicToOpenSearchIndex.EDFI_DOC_FIELD, "value");
        assertThatThrownBy(() -> newTransformation().apply(newRecord(recordValues)))
                .isInstanceOf(DataException.class)
                .hasMessageStartingWith("resourcename in value can't be null or empty");
    }

    @Test
    void Given_Record_With_ProjectName_And_ResourceName_Should_Rename_Topic_To_OpenSearch_Index() {
        final Map<String, Object> recordValues = new HashMap<>();
        recordValues.put(RenameDmsTopicToOpenSearchIndex.PROJECT_NAME_FIELD, "ProjectName");
        recordValues.put(RenameDmsTopicToOpenSearchIndex.RESOURCE_NAME_FIELD, "ResourceName");

        final SinkRecord result = newTransformation().apply(newRecord(recordValues));

        assertThat(result.topic()).isEqualTo("ProjectName$ResourceName");
    }

    protected RenameDmsTopicToOpenSearchIndex<SinkRecord> newTransformation() {
        return new RenameDmsTopicToOpenSearchIndex<>();
    }

    protected SinkRecord newRecord(final Object values) {
        return new SinkRecord("topic", 0, null, null, null, values, 123L, 456L, TimestampType.CREATE_TIME);
    }
}
