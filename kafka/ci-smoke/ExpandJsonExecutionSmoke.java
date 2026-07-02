// SPDX-License-Identifier: Apache-2.0
// Licensed to the Ed-Fi Alliance under one or more agreements.
// The Ed-Fi Alliance licenses this file to you under the Apache License, Version 2.0.
// See the LICENSE and NOTICES files in the project root for more information.

import java.util.List;
import java.util.Map;

import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.sink.SinkRecord;

import org.edfi.kafka.connect.transforms.ExpandJson;

// CI execution smoke, run inside the published image by .github/workflows/on-pullrequest.yml.
// The connect-plugin-path.sh scan only proves the transform classes link; this applies
// ExpandJson$Value to one schema-backed record so the transform executes against the
// runtime-provided dependencies -- in particular Jackson, which the plugin compiles against but
// does not bundle (see ed-fi-kafka-connect-transforms/build.gradle) and which is first touched
// on apply(), not at load time.
public final class ExpandJsonExecutionSmoke {

    private ExpandJsonExecutionSmoke() {
    }

    public static void main(final String[] args) {
        final ExpandJson.Value<SinkRecord> transform = new ExpandJson.Value<>();
        transform.configure(Map.of("sourceFields", "documentJson"));

        final Schema schema = SchemaBuilder.struct()
                .field("documentJson", Schema.STRING_SCHEMA)
                .field("other", Schema.OPTIONAL_STRING_SCHEMA)
                .build();
        final Struct value = new Struct(schema)
                .put("documentJson", "{\"a\":1,\"b\":{\"c\":\"x\"},\"d\":[1,2]}")
                .put("other", "keep");

        final SinkRecord out = transform.apply(new SinkRecord("smoke", 0, null, null, schema, value, 0L));
        transform.close();

        final Struct outValue = (Struct) out.value();
        final Struct document = (Struct) outValue.get("documentJson");
        expect(Long.valueOf(1L).equals(document.get("a")), "documentJson.a = " + document.get("a"));
        expect("x".equals(((Struct) document.get("b")).get("c")), "documentJson.b.c");
        expect(List.of(1L, 2L).equals(document.get("d")), "documentJson.d = " + document.get("d"));
        expect("keep".equals(outValue.get("other")), "sibling field 'other'");
        System.out.println("OK: ExpandJson expanded a schema-backed record on the image runtime classpath.");
    }

    private static void expect(final boolean condition, final String detail) {
        if (!condition) {
            throw new IllegalStateException("ExpandJson execution smoke failed: " + detail);
        }
    }
}
