// SPDX-License-Identifier: Apache-2.0
// Licensed to the Ed-Fi Alliance under one or more agreements.
// The Ed-Fi Alliance licenses this file to you under the Apache License, Version 2.0.
// See the LICENSE and NOTICES files in the project root for more information.

package org.edfi.kafka.connect.transforms;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ServiceLoaderManifestTest {

    private static final String SERVICE_LOADER_RESOURCE = "/META-INF/services/"
            + "org.apache.kafka.connect.transforms.Transformation";

    @Test
    void Should_Advertise_Current_Transformations() throws IOException {
        final byte[] resource = getClass().getResourceAsStream(SERVICE_LOADER_RESOURCE).readAllBytes();
        final List<String> providers = new String(resource, StandardCharsets.UTF_8)
                .lines()
                .filter(line -> !line.isBlank())
                .toList();

        assertThat(providers).containsExactly(
                "org.edfi.kafka.connect.transforms.DebeziumDeletedToTombstone",
                "org.edfi.kafka.connect.transforms.ExpandJson$Value");
    }
}
