// SPDX-License-Identifier: Apache-2.0
// Licensed to the Ed-Fi Alliance under one or more agreements.
// The Ed-Fi Alliance licenses this file to you under the Apache License, Version 2.0.
// See the LICENSE and NOTICES files in the project root for more information.

package org.edfi.kafka.connect.transforms;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.kafka.connect.errors.DataException;
import org.apache.kafka.connect.sink.SinkRecord;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CastHierarchyToInt64Test {

    @Test
    void Given_Record_With_Null_Value_Should_Return_Original_Record() {
        final SinkRecord result = newTransformation().apply(newRecord(null));
        assertThat(result.value()).isNull();
    }

    @Test
    void Given_Record_With_Non_Map_Value_Should_Throw_Exception() {
        assertThatThrownBy(() -> newTransformation().apply(newRecord(new Object())))
                .isInstanceOf(DataException.class)
                .hasMessageStartingWith("Record value is not a Map");
    }

    @Test
    void Given_Record_With_Hierarchy_As_List_Of_Numbers_Should_Cast_To_Int64() {
        final Map<String, Object> recordValues = new HashMap<>();
        recordValues.put("hierarchy", new Long[] {2L, 600L, 9999999L});
        
        final SinkRecord result = newTransformation().apply(newRecord(recordValues));
        
        assertThat(result.value()).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        final Map<String, Object> resultValue = (Map<String, Object>) result.value();

        assertThat(resultValue.get("hierarchy")).isEqualTo(new Long[] {2L, 600L, 9999999L});
    }
    

    @Test
    void Given_Record_With_Hierarchy_As_List_Of_Strings_Should_Cast_To_Int64() {
        final Map<String, Object> recordValues = new HashMap<>();
        recordValues.put("hierarchy", List.of("2", "600", "9999999"));
    
        final SinkRecord result = newTransformation().apply(newRecord(recordValues));
    
        assertThat(result.value()).isInstanceOf(Map.class); 
        @SuppressWarnings("unchecked")
        final Map<String, Object> resultValue = (Map<String, Object>) result.value();
    
        assertThat(resultValue.get("hierarchy")).isEqualTo(List.of(2L, 600L, 9999999L)); 
    }

    @Test
    void Given_Record_With_Hierarchy_As_Mixed_Values_Should_Cast_All_To_Int64() {
        final Map<String, Object> recordValues = new HashMap<>();
        recordValues.put("hierarchy", List.of("2", 600, 9999999L));
    
        final SinkRecord result = newTransformation().apply(newRecord(recordValues));
    
        assertThat(result.value()).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        final Map<String, Object> resultValue = (Map<String, Object>) result.value();
        assertThat(resultValue.get("hierarchy")).isEqualTo(List.of(2L, 600L, 9999999L));
    }

    @Test
    void Given_Record_Without_Hierarchy_Should_Not_Modify_Record() {
        final Map<String, Object> recordValues = new HashMap<>();
        recordValues.put("someOtherField", "value");

        final SinkRecord result = newTransformation().apply(newRecord(recordValues));

        assertThat(result.value()).isEqualTo(recordValues);
    }

    protected CastHierarchyToInt64<SinkRecord> newTransformation() {
        return new CastHierarchyToInt64<>();
    }

    protected SinkRecord newRecord(final Object values) {
        return new SinkRecord("topic", 0, null, null, null, values, 123L, 456L,
                org.apache.kafka.common.record.TimestampType.CREATE_TIME);
    }
}
