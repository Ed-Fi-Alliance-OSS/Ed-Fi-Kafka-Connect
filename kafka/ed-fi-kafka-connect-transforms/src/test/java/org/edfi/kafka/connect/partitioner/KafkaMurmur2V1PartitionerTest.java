// SPDX-License-Identifier: Apache-2.0
// Licensed to the Ed-Fi Alliance under one or more agreements.
// The Ed-Fi Alliance licenses this file to you under the Apache License, Version 2.0.
// See the LICENSE and NOTICES files in the project root for more information.

package org.edfi.kafka.connect.partitioner;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.apache.kafka.common.Cluster;
import org.apache.kafka.common.InvalidRecordException;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.PartitionInfo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KafkaMurmur2V1PartitionerTest {

    private static final String TOPIC = "edfi.documents";

    @ParameterizedTest
    @MethodSource("partitionVectors")
    void Given_Serialized_Key_Should_Match_Kafka_Murmur2_V1_Vector(
            final String key,
            final int partitionCount,
            final int expectedPartition) {
        final KafkaMurmur2V1Partitioner partitioner = new KafkaMurmur2V1Partitioner();

        final int partition = partitioner.partition(
                TOPIC,
                key,
                key.getBytes(StandardCharsets.UTF_8),
                null,
                null,
                cluster(partitionCount));

        assertThat(partition).isEqualTo(expectedPartition);
    }

    @Test
    void Given_Null_KeyBytes_Should_Fail_Closed() {
        final KafkaMurmur2V1Partitioner partitioner = new KafkaMurmur2V1Partitioner();

        assertThatThrownBy(() -> partitioner.partition(TOPIC, null, null, null, null, cluster(10)))
                .isInstanceOf(InvalidRecordException.class)
                .hasMessageContaining("non-null serialized key bytes");
    }

    @Test
    void Given_No_Topic_Partitions_Should_Fail_Closed() {
        final KafkaMurmur2V1Partitioner partitioner = new KafkaMurmur2V1Partitioner();

        assertThatThrownBy(() -> partitioner.partition(
                TOPIC,
                "document-0001",
                "document-0001".getBytes(StandardCharsets.UTF_8),
                null,
                null,
                cluster(0)))
                .isInstanceOf(InvalidRecordException.class)
                .hasMessageContaining("at least one topic partition");
    }

    @Test
    void Given_Configuration_Should_Accept_Empty_Map() {
        final KafkaMurmur2V1Partitioner partitioner = new KafkaMurmur2V1Partitioner();

        partitioner.configure(Map.of());
        partitioner.close();
    }

    private static Stream<Arguments> partitionVectors() {
        return Stream.of(
                Arguments.of("document-0001", 10, 6),
                Arguments.of("document-0002", 10, 4),
                Arguments.of("ed-fi/schools/255901001", 10, 8),
                Arguments.of("00000000-0000-0000-0000-000000000001", 10, 0));
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
