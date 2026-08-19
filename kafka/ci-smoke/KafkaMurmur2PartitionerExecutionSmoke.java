// SPDX-License-Identifier: Apache-2.0
// Licensed to the Ed-Fi Alliance under one or more agreements.
// The Ed-Fi Alliance licenses this file to you under the Apache License, Version 2.0.
// See the LICENSE and NOTICES files in the project root for more information.

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.kafka.clients.producer.Partitioner;
import org.apache.kafka.common.Cluster;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.PartitionInfo;

// CI execution smoke, run inside the built Ed-Fi image by .github/workflows/on-pullrequest.yml.
// Unit tests prove edge cases. This proves the packaged partitioner loads and preserves the DMS token vectors
// on the image runtime classpath.
public final class KafkaMurmur2PartitionerExecutionSmoke {

    private static final String TOPIC = "edfi.documents";

    private KafkaMurmur2PartitionerExecutionSmoke() {
    }

    public static void main(final String[] args) throws Exception {
        @SuppressWarnings("unchecked")
        final Class<? extends Partitioner> partitionerType =
                (Class<? extends Partitioner>) Class.forName(
                        "org.edfi.kafka.connect.partitioner.KafkaMurmur2V1Partitioner")
                        .asSubclass(Partitioner.class);
        final Partitioner partitioner = partitionerType.getDeclaredConstructor().newInstance();
        partitioner.configure(Collections.emptyMap());
        try {
            assertPartition(partitioner, "document-0001", 10, 6);
            assertPartition(partitioner, "document-0002", 10, 4);
            assertPartition(partitioner, "ed-fi/schools/255901001", 10, 8);
            assertPartition(partitioner, "00000000-0000-0000-0000-000000000001", 10, 0);
        } finally {
            partitioner.close();
        }

        System.out.println("OK: KafkaMurmur2V1Partitioner matched DMS vectors on the image runtime classpath.");
    }

    private static void assertPartition(
            final Partitioner partitioner,
            final String key,
            final int partitionCount,
            final int expected) {
        final byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        final int actual = partitioner.partition(TOPIC, key, keyBytes, null, null, cluster(partitionCount));
        if (actual != expected) {
            throw new IllegalStateException(key + " expected partition " + expected + " but was " + actual);
        }
    }

    private static Cluster cluster(final int partitionCount) {
        final List<PartitionInfo> partitions = new ArrayList<>();
        for (int partition = 0; partition < partitionCount; partition++) {
            partitions.add(new PartitionInfo(TOPIC, partition, null, new Node[0], new Node[0]));
        }

        return new Cluster(
                "cdc-template",
                Collections.emptyList(),
                partitions,
                Collections.emptySet(),
                Collections.emptySet());
    }
}
