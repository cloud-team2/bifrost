package com.bifrost.ops.internalops.dto;

import java.util.List;

/** get_cluster_info — Kafka 클러스터/브로커/토픽·파티션 상세(#633 범용 read 프리미티브). */
public record ClusterInfoResult(
        String clusterId,
        Integer controllerId,
        int brokerCount,
        List<BrokerInfo> brokers,
        List<TopicInfo> topics
) {
    public ClusterInfoResult {
        brokers = brokers == null ? List.of() : List.copyOf(brokers);
        topics = topics == null ? List.of() : List.copyOf(topics);
    }

    public record BrokerInfo(int id, String host, int port, boolean controller) {}

    public record TopicInfo(
            String name,
            int partitionCount,
            int replicationFactor,
            int underReplicatedPartitions,
            int offlinePartitions,
            List<PartitionInfo> partitions
    ) {
        public TopicInfo {
            partitions = partitions == null ? List.of() : List.copyOf(partitions);
        }
    }

    public record PartitionInfo(int partition, Integer leader, List<Integer> replicas, List<Integer> isr) {
        public PartitionInfo {
            replicas = replicas == null ? List.of() : List.copyOf(replicas);
            isr = isr == null ? List.of() : List.copyOf(isr);
        }
    }
}
