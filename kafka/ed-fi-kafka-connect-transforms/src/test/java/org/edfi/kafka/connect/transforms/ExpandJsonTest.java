// SPDX-License-Identifier: Apache-2.0
// Licensed to the Ed-Fi Alliance under one or more agreements.
// The Ed-Fi Alliance licenses this file to you under the Apache License, Version 2.0.
// See the LICENSE and NOTICES files in the project root for more information.

package org.edfi.kafka.connect.transforms;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.kafka.common.config.ConfigException;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.errors.DataException;
import org.apache.kafka.connect.json.JsonConverter;
import org.apache.kafka.connect.sink.SinkRecord;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    @Test
    void Given_Null_Value_Should_Return_Unchanged() {
        // A tombstone (null value, no schema) passes through; the schema-backed requirement
        // applies only to records that carry a value.
        final SinkRecord result = transform("payload").apply(schemalessRecord(null));
        assertThat(result.value()).isNull();
    }

    @Test
    void Given_Schemaless_Value_Should_Fail() {
        // DMS-1240 design contract: the input is a schema-backed value record (the Debezium
        // source pipeline). A record carrying a value without a schema is a misconfiguration.
        final Map<String, Object> value = new HashMap<>();
        value.put("payload", "{\"a\":1}");

        assertThatThrownBy(() -> transform("payload").apply(schemalessRecord(value)))
                .isInstanceOf(DataException.class)
                .hasMessageContaining("schema");
    }

    @Test
    void Given_Empty_SourceFields_Should_Throw_ConfigException() {
        assertThatThrownBy(() -> transform()).isInstanceOf(ConfigException.class);
    }

    @Test
    void Given_Missing_SourceFields_Should_Throw_ConfigException() {
        final ExpandJson<SinkRecord> smt = new ExpandJson.Value<>();
        assertThatThrownBy(() -> smt.configure(new HashMap<>())).isInstanceOf(ConfigException.class);
    }

    @Test
    void Given_Empty_String_SourceFields_Entry_Should_Throw_ConfigException() {
        assertThatThrownBy(() -> transform("")).isInstanceOf(ConfigException.class);
    }

    @Test
    void Given_Whitespace_SourceFields_Entry_Should_Throw_ConfigException() {
        assertThatThrownBy(() -> transform("payload", " ")).isInstanceOf(ConfigException.class);
    }

    @Test
    void Given_String_Config_With_Empty_Entry_Should_Throw_ConfigException() {
        final ExpandJson<SinkRecord> smt = new ExpandJson.Value<>();
        final Map<String, Object> config = new HashMap<>();
        config.put(ExpandJson.SOURCE_FIELDS_CONFIG, "payload,,other");
        assertThatThrownBy(() -> smt.configure(config)).isInstanceOf(ConfigException.class);
    }

    @Test
    void Given_SourceFields_Entry_With_Whitespace_Should_Be_Trimmed() {
        final Schema schema = stringSchema("payload");
        final Struct value = new Struct(schema).put("payload", "{\"a\":1}");

        final Struct out = (Struct) transform(" payload ").apply(schemaRecord(schema, value)).value();

        assertThat(((Struct) out.get("payload")).get("a")).isEqualTo(1L);
    }

    @Test
    void Given_Multiple_SourceFields_Should_Expand_Each() {
        final Schema schema = SchemaBuilder.struct()
                .field("a", Schema.STRING_SCHEMA)
                .field("b", Schema.STRING_SCHEMA)
                .build();
        final Struct value = new Struct(schema)
                .put("a", "{\"x\":1}")
                .put("b", "{\"y\":2}");

        final Struct out = (Struct) transform("a", "b").apply(schemaRecord(schema, value)).value();

        assertThat(((Struct) out.get("a")).get("x")).isEqualTo(1L);
        assertThat(((Struct) out.get("b")).get("y")).isEqualTo(2L);
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
    void Given_SchemaBacked_Root_Schema_Metadata_Should_Be_Preserved() {
        final Schema schema = SchemaBuilder.struct()
                .name("org.edfi.Envelope")
                .version(3)
                .doc("root doc")
                .parameter("connect.origin", "dms")
                .field("payload", Schema.STRING_SCHEMA)
                .build();
        final Struct value = new Struct(schema).put("payload", "{\"a\":1}");

        final Schema out = transform("payload").apply(schemaRecord(schema, value)).valueSchema();

        assertThat(out.name()).isEqualTo("org.edfi.Envelope");
        assertThat(out.version()).isEqualTo(3);
        assertThat(out.doc()).isEqualTo("root doc");
        assertThat(out.parameters()).containsEntry("connect.origin", "dms");
    }

    @Test
    void Given_SchemaBacked_Root_Schema_With_Struct_Default_Should_Expand_Without_Failing() {
        // A root struct default is bound to the original field schemas; once "payload" is expanded
        // from STRING to STRUCT the default can no longer be carried onto the rebuilt schema. Build
        // the default against the builder instance itself, since Connect validates a struct default
        // by schema identity.
        final SchemaBuilder builder = SchemaBuilder.struct().field("payload", Schema.STRING_SCHEMA);
        final Struct rootDefault = new Struct(builder).put("payload", "{}");
        final Schema schema = builder.defaultValue(rootDefault).build();
        final Struct value = new Struct(schema).put("payload", "{\"a\":1}");

        final Struct out = (Struct) transform("payload").apply(schemaRecord(schema, value)).value();

        assertThat(out.schema().field("payload").schema().type()).isEqualTo(Schema.Type.STRUCT);
        assertThat(((Struct) out.get("payload")).get("a")).isEqualTo(1L);
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
    void Given_SchemaBacked_Null_Only_Property_Should_Infer_Optional_String_Schema() {
        // Documents the inference fallback: a property with no type evidence in this record
        // (JSON null) is typed as optional STRING; the value still expands to null.
        final Schema schema = stringSchema("payload");
        final Struct value = new Struct(schema).put("payload", "{\"a\":null}");

        final Struct out = (Struct) transform("payload").apply(schemaRecord(schema, value)).value();

        final Schema inferred = out.schema().field("payload").schema().field("a").schema();
        assertThat(inferred.type()).isEqualTo(Schema.Type.STRING);
        assertThat(inferred.isOptional()).isTrue();
        assertThat(((Struct) out.get("payload")).get("a")).isNull();
    }

    @Test
    void Given_SchemaBacked_Empty_Array_Should_Infer_String_Element_Schema() {
        // Documents the inference fallback: an array with no non-null elements carries no type
        // evidence, so its element schema is optional STRING for this record.
        final Schema schema = stringSchema("payload");
        final Struct value = new Struct(schema).put("payload", "{\"arr\":[]}");

        final Struct out = (Struct) transform("payload").apply(schemaRecord(schema, value)).value();

        final Schema arr = out.schema().field("payload").schema().field("arr").schema();
        assertThat(arr.type()).isEqualTo(Schema.Type.ARRAY);
        assertThat(arr.valueSchema().type()).isEqualTo(Schema.Type.STRING);
        assertThat(arr.valueSchema().isOptional()).isTrue();
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
    void Given_SchemaBacked_Integral_Above_Long_Range_Should_Fail() {
        final Schema schema = stringSchema("payload");
        final Struct value = new Struct(schema).put("payload", "{\"n\":9223372036854775808}");

        assertThatThrownBy(() -> transform("payload").apply(schemaRecord(schema, value)))
                .isInstanceOf(DataException.class)
                .hasMessageContaining("9223372036854775808");
    }

    @Test
    void Given_SchemaBacked_Integral_Below_Long_Range_Should_Fail() {
        final Schema schema = stringSchema("payload");
        final Struct value = new Struct(schema).put("payload", "{\"n\":-9223372036854775809}");

        assertThatThrownBy(() -> transform("payload").apply(schemaRecord(schema, value)))
                .isInstanceOf(DataException.class);
    }

    @Test
    void Given_SchemaBacked_Long_Boundary_Values_Should_Expand_Exactly() {
        final Schema schema = stringSchema("payload");
        final Struct value = new Struct(schema)
                .put("payload", "{\"max\":9223372036854775807,\"min\":-9223372036854775808}");

        final Struct out = (Struct) transform("payload").apply(schemaRecord(schema, value)).value();

        final Struct payload = (Struct) out.get("payload");
        assertThat(payload.get("max")).isEqualTo(Long.MAX_VALUE);
        assertThat(payload.get("min")).isEqualTo(Long.MIN_VALUE);
    }

    @Test
    void Given_SchemaBacked_High_Precision_Decimal_Should_Round_To_Nearest_Double() {
        // Documents the numeric contract: decimals map to FLOAT64 (IEEE 754 double), so precision
        // beyond a double is rounded, not preserved and not an error.
        final Schema schema = stringSchema("payload");
        final Struct value = new Struct(schema).put("payload", "{\"d\":0.10000000000000000000001}");

        final Struct out = (Struct) transform("payload").apply(schemaRecord(schema, value)).value();

        assertThat(((Struct) out.get("payload")).get("d")).isEqualTo(0.1d);
    }

    @Test
    void Given_SchemaBacked_Decimal_Beyond_Double_Range_Should_Fail() {
        final Schema schema = stringSchema("payload");
        final Struct value = new Struct(schema).put("payload", "{\"d\":1e999}");

        assertThatThrownBy(() -> transform("payload").apply(schemaRecord(schema, value)))
                .isInstanceOf(DataException.class);
    }

    @Test
    void Given_SchemaBacked_Mixed_Numeric_Array_Should_Promote_To_Double() {
        final Schema schema = stringSchema("payload");
        final Struct value = new Struct(schema).put("payload", "{\"arr\":[1,2.5]}");

        final Struct out = (Struct) transform("payload").apply(schemaRecord(schema, value)).value();

        final Struct payload = (Struct) out.get("payload");
        assertThat(payload.schema().field("arr").schema().valueSchema().type()).isEqualTo(Schema.Type.FLOAT64);
        assertThat(payload.get("arr")).isEqualTo(List.of(1.0d, 2.5d));
    }

    @Test
    void Given_SchemaBacked_Object_Array_Mixed_Numeric_Field_Should_Promote_To_Double() {
        final Schema schema = stringSchema("payload");
        final Struct value = new Struct(schema).put("payload", "{\"arr\":[{\"score\":1},{\"score\":2.5}]}");

        final Struct out = (Struct) transform("payload").apply(schemaRecord(schema, value)).value();

        final List<?> arr = (List<?>) ((Struct) out.get("payload")).get("arr");
        final Struct first = (Struct) arr.get(0);
        final Struct second = (Struct) arr.get(1);
        assertThat(first.schema().field("score").schema().type()).isEqualTo(Schema.Type.FLOAT64);
        assertThat(first.get("score")).isEqualTo(1.0d);
        assertThat(second.get("score")).isEqualTo(2.5d);
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
    void Given_SchemaBacked_Null_Field_With_Blank_Schema_Default_Should_Be_Noop() {
        // Struct.get substitutes the field schema's default for a null value (e.g. a column
        // DEFAULT '' propagated by Debezium); a null field must still be skipped, not parsed.
        final Schema schema = SchemaBuilder.struct()
                .field("payload", SchemaBuilder.string().optional().defaultValue("").build())
                .build();
        final Struct value = new Struct(schema).put("payload", null);

        final SinkRecord result = transform("payload").apply(schemaRecord(schema, value));

        assertThat(result.value()).isSameAs(value);
    }

    @Test
    void Given_SchemaBacked_Null_Field_With_Object_Schema_Default_Should_Be_Noop() {
        final Schema schema = SchemaBuilder.struct()
                .field("payload", SchemaBuilder.string().optional().defaultValue("{}").build())
                .build();
        final Struct value = new Struct(schema).put("payload", null);

        final SinkRecord result = transform("payload").apply(schemaRecord(schema, value));

        assertThat(result.value()).isSameAs(value);
    }

    @Test
    void Given_SchemaBacked_Null_Non_String_Field_Should_Fail() {
        final Schema schema = SchemaBuilder.struct()
                .field("payload", Schema.OPTIONAL_INT32_SCHEMA)
                .build();
        final Struct value = new Struct(schema).put("payload", null);

        assertThatThrownBy(() -> transform("payload").apply(schemaRecord(schema, value)))
                .isInstanceOf(DataException.class)
                .hasMessageContaining("STRING");
    }

    @Test
    void Given_SchemaBacked_Null_Sibling_With_Schema_Default_Should_Stay_Null_After_Expansion() {
        final Schema schema = SchemaBuilder.struct()
                .field("payload", Schema.STRING_SCHEMA)
                .field("status", SchemaBuilder.string().optional().defaultValue("active").build())
                .build();
        final Struct value = new Struct(schema).put("payload", "{\"a\":1}").put("status", null);

        final Struct out = (Struct) transform("payload").apply(schemaRecord(schema, value)).value();

        assertThat(out.getWithoutDefault("status")).isNull();
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
    void Given_SchemaBacked_Json_With_Trailing_Tokens_Should_Fail() {
        final Schema schema = stringSchema("payload");
        final Struct value = new Struct(schema).put("payload", "{\"a\":1} true");

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
    void Given_SchemaBacked_Output_Serialized_Without_Schemas_Should_Be_Structured() throws Exception {
        final Schema schema = SchemaBuilder.struct()
                .field("payload", Schema.STRING_SCHEMA)
                .field("other", Schema.OPTIONAL_STRING_SCHEMA)
                .build();
        final Struct value = new Struct(schema)
                .put("payload", "{\"a\":1,\"b\":\"x\",\"c\":{\"d\":true},\"e\":[1,2]}")
                .put("other", "keep");
        final SinkRecord result = transform("payload").apply(schemaRecord(schema, value));

        final JsonConverter converter = new JsonConverter();
        converter.configure(Map.of("schemas.enable", "false"), false);
        final byte[] bytes = converter.fromConnectData(TOPIC, result.valueSchema(), result.value());

        // Parse the serialized bytes: the expanded field must be a real nested JSON object with
        // the correct nested values, not an escaped JSON string.
        final JsonNode root = new ObjectMapper().readTree(bytes);
        final JsonNode payload = root.get("payload");
        assertThat(payload.isObject()).isTrue();
        assertThat(payload.get("a").isNumber()).isTrue();
        assertThat(payload.get("a").asLong()).isEqualTo(1L);
        assertThat(payload.get("b").asText()).isEqualTo("x");
        assertThat(payload.get("c").isObject()).isTrue();
        assertThat(payload.get("c").get("d").asBoolean()).isTrue();
        assertThat(payload.get("e").isArray()).isTrue();
        assertThat(payload.get("e").get(0).asLong()).isEqualTo(1L);
        assertThat(payload.get("e").get(1).asLong()).isEqualTo(2L);
        assertThat(root.get("other").asText()).isEqualTo("keep");
    }
}
