// SPDX-License-Identifier: Apache-2.0
// Licensed to the Ed-Fi Alliance under one or more agreements.
// The Ed-Fi Alliance licenses this file to you under the Apache License, Version 2.0.
// See the LICENSE and NOTICES files in the project root for more information.

package org.edfi.kafka.connect.transforms;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.apache.kafka.connect.data.Decimal;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.errors.DataException;
import org.apache.kafka.connect.json.JsonConverter;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentStateDecimalTest {

    private static final String TOPIC = "edfi.document-state";
    private static final BigDecimal HIGH_PRECISION_DECIMAL =
            new BigDecimal("3.141592653589793238462643383279");
    private static final ObjectMapper DECIMAL_MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    @Test
    void Given_SchemaBacked_Decimals_And_Numeric_Format_Should_Serialize_Exact_Public_Json_Numbers()
            throws Exception {
        final Schema nestedSchema = SchemaBuilder.struct()
                .field("gradePointAverage", Decimal.schema(HIGH_PRECISION_DECIMAL.scale()))
                .build();
        final Schema arrayDecimalSchema = Decimal.schema(6);
        final Schema valueSchema = SchemaBuilder.struct()
                .field("topLevelDecimal", Decimal.schema(HIGH_PRECISION_DECIMAL.scale()))
                .field("nested", nestedSchema)
                .field("scores", SchemaBuilder.array(arrayDecimalSchema).build())
                .build();
        final BigDecimal firstScore = new BigDecimal("12.345678");
        final BigDecimal secondScore = new BigDecimal("0.000001");
        final Struct value = new Struct(valueSchema)
                .put("topLevelDecimal", HIGH_PRECISION_DECIMAL)
                .put("nested", new Struct(nestedSchema)
                        .put("gradePointAverage", HIGH_PRECISION_DECIMAL))
                .put("scores", List.of(firstScore, secondScore));

        final JsonNode root = serializeAndParse(valueSchema, value, numericDecimalConverter());

        assertThat(root.has("schema")).isFalse();
        assertThat(root.has("payload")).isFalse();
        assertNumericDecimal(root.get("topLevelDecimal"), HIGH_PRECISION_DECIMAL);
        assertNumericDecimal(root.get("nested").get("gradePointAverage"), HIGH_PRECISION_DECIMAL);
        assertNumericDecimal(root.get("scores").get(0), firstScore);
        assertNumericDecimal(root.get("scores").get(1), secondScore);
    }

    @Test
    void Given_Mixed_Scale_Decimal_Array_Should_Serialize_As_Numeric_Values() throws Exception {
        final Schema valueSchema = SchemaBuilder.struct()
                .field("scores", SchemaBuilder.array(Decimal.schema(2)).build())
                .build();
        final Struct value = new Struct(valueSchema)
                .put("scores", List.of(new BigDecimal("1"), new BigDecimal("1.20")));

        final JsonNode root = serializeAndParse(valueSchema, value, numericDecimalConverter());

        assertNumericDecimal(root.get("scores").get(0), new BigDecimal("1"));
        assertNumericDecimal(root.get("scores").get(1), new BigDecimal("1.20"));
    }

    @Test
    void Given_SchemaBacked_Decimals_Without_Numeric_Format_Should_Not_Serialize_Public_Json_Numbers()
            throws Exception {
        final Schema valueSchema = SchemaBuilder.struct()
                .field("topLevelDecimal", Decimal.schema(HIGH_PRECISION_DECIMAL.scale()))
                .build();
        final Struct value = new Struct(valueSchema)
                .put("topLevelDecimal", HIGH_PRECISION_DECIMAL);

        final JsonNode root = serializeAndParse(valueSchema, value, defaultDecimalConverter());

        assertThat(root.has("schema")).isFalse();
        assertThat(root.has("payload")).isFalse();
        assertThat(root.get("topLevelDecimal").isNumber()).isFalse();
    }

    @Test
    void Given_Schemaless_BigDecimal_Map_Should_Fail_Even_With_Numeric_Format() {
        final JsonConverter converter = numericDecimalConverter();

        assertThatThrownBy(() -> converter.fromConnectData(
                TOPIC,
                null,
                Map.of("topLevelDecimal", HIGH_PRECISION_DECIMAL)))
                .isInstanceOf(DataException.class);
    }

    private static JsonConverter numericDecimalConverter() {
        final JsonConverter converter = new JsonConverter();
        converter.configure(Map.of(
                "schemas.enable", "false",
                "decimal.format", "NUMERIC"), false);
        return converter;
    }

    private static JsonConverter defaultDecimalConverter() {
        final JsonConverter converter = new JsonConverter();
        converter.configure(Map.of("schemas.enable", "false"), false);
        return converter;
    }

    private static JsonNode serializeAndParse(
            final Schema schema,
            final Struct value,
            final JsonConverter converter)
            throws Exception {
        return DECIMAL_MAPPER.readTree(converter.fromConnectData(TOPIC, schema, value));
    }

    private static void assertNumericDecimal(final JsonNode node, final BigDecimal expected) {
        assertThat(node.isNumber()).isTrue();
        assertThat(node.isTextual()).isFalse();
        assertThat(node.decimalValue()).isEqualByComparingTo(expected);
    }
}
