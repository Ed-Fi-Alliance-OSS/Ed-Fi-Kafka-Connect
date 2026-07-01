// SPDX-License-Identifier: Apache-2.0
// Licensed to the Ed-Fi Alliance under one or more agreements.
// The Ed-Fi Alliance licenses this file to you under the Apache License, Version 2.0.
// See the LICENSE and NOTICES files in the project root for more information.

package org.edfi.kafka.connect.transforms;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.errors.DataException;
import org.apache.kafka.connect.json.JsonConverter;
import org.apache.kafka.connect.sink.SinkRecord;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExpandJsonTest {

    private static final String TOPIC = "topic";

    private ExpandJson<SinkRecord> transform(final String... sourceFields) {
        final ExpandJson<SinkRecord> smt = new ExpandJson.Value<>();
        final Map<String, Object> config = new HashMap<>();
        config.put(ExpandJson.SOURCE_FIELDS_CONFIG, List.of(sourceFields));
        smt.configure(config);
        return smt;
    }

    private SinkRecord schemalessRecord(final Object value) {
        return new SinkRecord(TOPIC, 0, null, null, null, value, 0L);
    }

    private SinkRecord schemaRecord(final Schema valueSchema, final Object value) {
        return new SinkRecord(TOPIC, 0, null, null, valueSchema, value, 0L);
    }

    private Schema stringSchema(final String field) {
        return SchemaBuilder.struct().field(field, Schema.STRING_SCHEMA).build();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> valueAsMap(final SinkRecord record) {
        return (Map<String, Object>) record.value();
    }

    @Test
    void Given_Schemaless_Null_Value_Should_Return_Unchanged() {
        final SinkRecord result = transform("payload").apply(schemalessRecord(null));
        assertThat(result.value()).isNull();
    }

    @Test
    void Given_Empty_SourceFields_Should_Leave_Record_Unchanged() {
        final Map<String, Object> value = new HashMap<>();
        value.put("payload", "{\"a\":1}");
        final SinkRecord result = transform().apply(schemalessRecord(value));
        assertThat(result.value()).isEqualTo(value);
    }

    @Test
    void Given_Schemaless_Json_Object_Field_Should_Expand() {
        final Map<String, Object> value = new HashMap<>();
        value.put("payload", "{\"a\":1,\"b\":\"x\"}");
        value.put("other", "keep");

        final Map<String, Object> out = valueAsMap(transform("payload").apply(schemalessRecord(value)));

        final Map<String, Object> expected = new HashMap<>();
        expected.put("a", 1);
        expected.put("b", "x");
        assertThat(out.get("payload")).isEqualTo(expected);
        assertThat(out.get("other")).isEqualTo("keep");
    }

    @Test
    void Given_Schemaless_Nested_Object_Should_Expand() {
        final Map<String, Object> value = new HashMap<>();
        value.put("payload", "{\"a\":{\"b\":1}}");

        final Map<String, Object> out = valueAsMap(transform("payload").apply(schemalessRecord(value)));

        assertThat(out.get("payload")).isEqualTo(Map.of("a", Map.of("b", 1)));
    }

    @Test
    void Given_Schemaless_Scalar_Array_Should_Expand() {
        final Map<String, Object> value = new HashMap<>();
        value.put("payload", "{\"arr\":[1,2,3]}");

        final Map<String, Object> out = valueAsMap(transform("payload").apply(schemalessRecord(value)));

        assertThat(out.get("payload")).isEqualTo(Map.of("arr", List.of(1, 2, 3)));
    }

    @Test
    void Given_Schemaless_Object_Array_Should_Expand() {
        final Map<String, Object> value = new HashMap<>();
        value.put("payload", "{\"arr\":[{\"a\":1},{\"a\":2}]}");

        final Map<String, Object> out = valueAsMap(transform("payload").apply(schemalessRecord(value)));

        assertThat(out.get("payload")).isEqualTo(Map.of("arr", List.of(Map.of("a", 1), Map.of("a", 2))));
    }

    @Test
    void Given_Schemaless_Empty_Array_Should_Expand() {
        final Map<String, Object> value = new HashMap<>();
        value.put("payload", "{\"arr\":[]}");

        final Map<String, Object> out = valueAsMap(transform("payload").apply(schemalessRecord(value)));

        assertThat(out.get("payload")).isEqualTo(Map.of("arr", List.of()));
    }

    @Test
    void Given_Schemaless_Null_Field_Should_Be_Noop() {
        final Map<String, Object> value = new HashMap<>();
        value.put("payload", null);
        value.put("other", "keep");

        final SinkRecord result = transform("payload").apply(schemalessRecord(value));

        assertThat(result.value()).isEqualTo(value);
    }

    @Test
    void Given_Schemaless_Missing_Field_Should_Be_Noop() {
        final Map<String, Object> value = new HashMap<>();
        value.put("other", "keep");

        final SinkRecord result = transform("payload").apply(schemalessRecord(value));

        assertThat(result.value()).isEqualTo(value);
    }

    @Test
    void Given_Schemaless_Non_String_Field_Should_Fail() {
        final Map<String, Object> value = new HashMap<>();
        value.put("payload", 123);

        assertThatThrownBy(() -> transform("payload").apply(schemalessRecord(value)))
                .isInstanceOf(DataException.class);
    }

    @Test
    void Given_Schemaless_Invalid_Json_Should_Fail() {
        final Map<String, Object> value = new HashMap<>();
        value.put("payload", "{not json");

        assertThatThrownBy(() -> transform("payload").apply(schemalessRecord(value)))
                .isInstanceOf(DataException.class);
    }

    @Test
    void Given_Schemaless_Root_Array_Should_Fail() {
        final Map<String, Object> value = new HashMap<>();
        value.put("payload", "[1,2,3]");

        assertThatThrownBy(() -> transform("payload").apply(schemalessRecord(value)))
                .isInstanceOf(DataException.class);
    }

    @Test
    void Given_Schemaless_Root_Scalar_Should_Fail() {
        final Map<String, Object> value = new HashMap<>();
        value.put("payload", "3");

        assertThatThrownBy(() -> transform("payload").apply(schemalessRecord(value)))
                .isInstanceOf(DataException.class);
    }

    @Test
    void Given_Schemaless_Non_Map_Value_Should_Fail() {
        assertThatThrownBy(() -> transform("payload").apply(schemalessRecord("not a map")))
                .isInstanceOf(DataException.class);
    }

    @Test
    void Given_Schemaless_Multiple_Fields_Should_Expand_Each() {
        final Map<String, Object> value = new HashMap<>();
        value.put("a", "{\"x\":1}");
        value.put("b", "{\"y\":2}");

        final Map<String, Object> out = valueAsMap(transform("a", "b").apply(schemalessRecord(value)));

        assertThat(out.get("a")).isEqualTo(Map.of("x", 1));
        assertThat(out.get("b")).isEqualTo(Map.of("y", 2));
    }

    @Test
    void Given_SchemaBacked_Json_Object_Field_Should_Expand() {
        final Schema schema = SchemaBuilder.struct()
                .field("payload", Schema.STRING_SCHEMA)
                .field("other", Schema.OPTIONAL_STRING_SCHEMA)
                .build();
        final Struct value = new Struct(schema)
                .put("payload", "{\"a\":1,\"b\":\"x\"}")
                .put("other", "keep");

        final SinkRecord result = transform("payload").apply(schemaRecord(schema, value));

        final Struct out = (Struct) result.value();
        assertThat(out.schema().field("payload").schema().type()).isEqualTo(Schema.Type.STRUCT);
        final Struct payload = (Struct) out.get("payload");
        assertThat(payload.get("a")).isEqualTo(1L);
        assertThat(payload.get("b")).isEqualTo("x");
        assertThat(out.get("other")).isEqualTo("keep");
    }

    @Test
    void Given_SchemaBacked_Nested_Object_Should_Expand() {
        final Schema schema = stringSchema("payload");
        final Struct value = new Struct(schema).put("payload", "{\"a\":{\"b\":1}}");

        final Struct out = (Struct) transform("payload").apply(schemaRecord(schema, value)).value();

        final Struct inner = ((Struct) out.get("payload")).getStruct("a");
        assertThat(inner.get("b")).isEqualTo(1L);
    }

    @Test
    void Given_SchemaBacked_Scalar_Array_Should_Expand() {
        final Schema schema = stringSchema("payload");
        final Struct value = new Struct(schema).put("payload", "{\"arr\":[1,2,3]}");

        final Struct out = (Struct) transform("payload").apply(schemaRecord(schema, value)).value();

        final Struct payload = (Struct) out.get("payload");
        assertThat(payload.schema().field("arr").schema().valueSchema().type()).isEqualTo(Schema.Type.INT64);
        assertThat(payload.get("arr")).isEqualTo(List.of(1L, 2L, 3L));
    }

    @Test
    void Given_SchemaBacked_Object_Array_With_Optional_Fields_Should_Union() {
        final Schema schema = stringSchema("payload");
        final Struct value = new Struct(schema).put("payload", "{\"arr\":[{\"a\":1},{\"b\":2}]}");

        final Struct out = (Struct) transform("payload").apply(schemaRecord(schema, value)).value();

        final List<?> arr = (List<?>) ((Struct) out.get("payload")).get("arr");
        final Struct first = (Struct) arr.get(0);
        final Struct second = (Struct) arr.get(1);
        assertThat(first.get("a")).isEqualTo(1L);
        assertThat(first.get("b")).isNull();
        assertThat(second.get("a")).isNull();
        assertThat(second.get("b")).isEqualTo(2L);
    }

    @Test
    void Given_SchemaBacked_Empty_Array_Should_Expand() {
        final Schema schema = stringSchema("payload");
        final Struct value = new Struct(schema).put("payload", "{\"arr\":[]}");

        final Struct out = (Struct) transform("payload").apply(schemaRecord(schema, value)).value();

        assertThat(((Struct) out.get("payload")).get("arr")).isEqualTo(List.of());
    }

    @Test
    void Given_SchemaBacked_Array_With_Nulls_Should_Be_Allowed() {
        final Schema schema = stringSchema("payload");
        final Struct value = new Struct(schema).put("payload", "{\"arr\":[1,null,3]}");

        final Struct out = (Struct) transform("payload").apply(schemaRecord(schema, value)).value();

        final Object arr = ((Struct) out.get("payload")).get("arr");
        assertThat(arr).isEqualTo(Arrays.asList(1L, null, 3L));
    }

    @Test
    void Given_SchemaBacked_Boolean_And_Double_Should_Map() {
        final Schema schema = stringSchema("payload");
        final Struct value = new Struct(schema).put("payload", "{\"b\":true,\"d\":1.5}");

        final Struct out = (Struct) transform("payload").apply(schemaRecord(schema, value)).value();

        final Struct payload = (Struct) out.get("payload");
        assertThat(payload.get("b")).isEqualTo(true);
        assertThat(payload.get("d")).isEqualTo(1.5d);
    }

    @Test
    void Given_SchemaBacked_Null_Field_Should_Be_Noop() {
        final Schema schema = SchemaBuilder.struct()
                .field("payload", Schema.OPTIONAL_STRING_SCHEMA)
                .field("other", Schema.OPTIONAL_STRING_SCHEMA)
                .build();
        final Struct value = new Struct(schema).put("payload", null).put("other", "keep");

        final SinkRecord result = transform("payload").apply(schemaRecord(schema, value));

        assertThat(result.value()).isSameAs(value);
    }

    @Test
    void Given_SchemaBacked_Missing_Field_Should_Be_Noop() {
        final Schema schema = stringSchema("other");
        final Struct value = new Struct(schema).put("other", "keep");

        final SinkRecord result = transform("payload").apply(schemaRecord(schema, value));

        assertThat(result.value()).isSameAs(value);
    }

    @Test
    void Given_SchemaBacked_Non_String_Field_Should_Fail() {
        final Schema schema = SchemaBuilder.struct().field("payload", Schema.INT32_SCHEMA).build();
        final Struct value = new Struct(schema).put("payload", 123);

        assertThatThrownBy(() -> transform("payload").apply(schemaRecord(schema, value)))
                .isInstanceOf(DataException.class);
    }

    @Test
    void Given_SchemaBacked_Invalid_Json_Should_Fail() {
        final Schema schema = stringSchema("payload");
        final Struct value = new Struct(schema).put("payload", "{bad");

        assertThatThrownBy(() -> transform("payload").apply(schemaRecord(schema, value)))
                .isInstanceOf(DataException.class);
    }

    @Test
    void Given_SchemaBacked_Root_Array_Should_Fail() {
        final Schema schema = stringSchema("payload");
        final Struct value = new Struct(schema).put("payload", "[1,2,3]");

        assertThatThrownBy(() -> transform("payload").apply(schemaRecord(schema, value)))
                .isInstanceOf(DataException.class);
    }

    @Test
    void Given_SchemaBacked_Mixed_Type_Array_Should_Fail() {
        final Schema schema = stringSchema("payload");
        final Struct value = new Struct(schema).put("payload", "{\"arr\":[1,\"x\"]}");

        assertThatThrownBy(() -> transform("payload").apply(schemaRecord(schema, value)))
                .isInstanceOf(DataException.class);
    }

    @Test
    void Given_SchemaBacked_Non_Struct_Value_Should_Fail() {
        assertThatThrownBy(() -> transform("payload").apply(schemaRecord(Schema.STRING_SCHEMA, "hello")))
                .isInstanceOf(DataException.class);
    }

    @Test
    void Given_SchemaBacked_Object_Array_Null_First_Field_Should_Use_NonNull_Type() {
        final Schema schema = stringSchema("payload");
        final Struct value = new Struct(schema).put("payload", "{\"arr\":[{\"a\":null},{\"a\":1}]}");

        final Struct out = (Struct) transform("payload").apply(schemaRecord(schema, value)).value();

        final List<?> arr = (List<?>) ((Struct) out.get("payload")).get("arr");
        final Struct second = (Struct) arr.get(1);
        assertThat(second.schema().field("a").schema().type()).isEqualTo(Schema.Type.INT64);
        assertThat(((Struct) arr.get(0)).get("a")).isNull();
        assertThat(second.get("a")).isEqualTo(1L);
    }

    @Test
    void Given_SchemaBacked_Object_Array_Nested_Object_Fields_Should_Union() {
        final Schema schema = stringSchema("payload");
        final Struct value = new Struct(schema)
                .put("payload", "{\"arr\":[{\"a\":{\"x\":1}},{\"a\":{\"y\":2}}]}");

        final Struct out = (Struct) transform("payload").apply(schemaRecord(schema, value)).value();

        final List<?> arr = (List<?>) ((Struct) out.get("payload")).get("arr");
        final Struct first = ((Struct) arr.get(0)).getStruct("a");
        final Struct second = ((Struct) arr.get(1)).getStruct("a");
        assertThat(first.get("x")).isEqualTo(1L);
        assertThat(first.get("y")).isNull();
        assertThat(second.get("x")).isNull();
        assertThat(second.get("y")).isEqualTo(2L);
    }

    @Test
    void Given_SchemaBacked_Object_Array_Incompatible_Field_Types_Should_Fail() {
        final Schema schema = stringSchema("payload");
        final Struct value = new Struct(schema).put("payload", "{\"arr\":[{\"a\":1},{\"a\":\"x\"}]}");

        assertThatThrownBy(() -> transform("payload").apply(schemaRecord(schema, value)))
                .isInstanceOf(DataException.class);
    }

    @Test
    void Given_SchemaBacked_Output_Serialized_Without_Schemas_Should_Be_Structured() {
        final Schema schema = stringSchema("payload");
        final Struct value = new Struct(schema).put("payload", "{\"a\":1,\"b\":\"x\"}");
        final SinkRecord result = transform("payload").apply(schemaRecord(schema, value));

        final JsonConverter converter = new JsonConverter();
        converter.configure(Map.of("schemas.enable", "false"), false);
        final byte[] bytes = converter.fromConnectData(TOPIC, result.valueSchema(), result.value());
        final String json = new String(bytes, StandardCharsets.UTF_8);

        // The expanded field is real nested JSON, not an escaped JSON string.
        assertThat(json).contains("\"payload\":{");
        assertThat(json).doesNotContain("\\\"");
    }
}
