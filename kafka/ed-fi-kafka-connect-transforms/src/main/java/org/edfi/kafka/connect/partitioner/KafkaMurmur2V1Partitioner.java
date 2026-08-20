// SPDX-License-Identifier: Apache-2.0
// Licensed to the Ed-Fi Alliance under one or more agreements.
// The Ed-Fi Alliance licenses this file to you under the Apache License, Version 2.0.
// See the LICENSE and NOTICES files in the project root for more information.

package org.edfi.kafka.connect.partitioner;

import java.util.List;
import java.util.Map;

import org.apache.kafka.clients.producer.Partitioner;
import org.apache.kafka.common.Cluster;
import org.apache.kafka.common.InvalidRecordException;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.utils.Utils;

public final class KafkaMurmur2V1Partitioner implements Partitioner {

    @Override
    public void configure(final Map<String, ?> configs) {
    }

    @Override
    public int partition(
            final String topic,
            final Object key,
            final byte[] keyBytes,
            final Object value,
            final byte[] valueBytes,
            final Cluster cluster) {
        if (keyBytes == null) {
            throw new InvalidRecordException("kafka-murmur2-v1 requires non-null serialized key bytes.");
        }

        final List<PartitionInfo> partitions = cluster.partitionsForTopic(topic);
        if (partitions == null || partitions.isEmpty()) {
            throw new InvalidRecordException("kafka-murmur2-v1 requires at least one topic partition.");
        }

        return Utils.toPositive(Utils.murmur2(keyBytes)) % partitions.size();
    }

    @Override
    public void close() {
    }
}
