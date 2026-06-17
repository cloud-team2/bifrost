package com.bifrost.ops.internalops.dto;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public record ConsumerLagResult(
        String consumerGroup,
        long totalLag,
        String source,
        List<PartitionLag> partitions,
        Instant observedAt,
        Double p95Lag,
        List<PartitionLag> topLagPartitions,
        String summary) {

    public ConsumerLagResult(String consumerGroup, long totalLag, String source) {
        this(consumerGroup, totalLag, source, List.of(), null, null, List.of(), null);
    }

    public static ConsumerLagResult fromSnapshot(
            String consumerGroup,
            long totalLag,
            String source,
            List<PartitionLag> partitions,
            Instant observedAt) {
        List<PartitionLag> safePartitions = partitions == null ? List.of() : List.copyOf(partitions);
        Double p95Lag = percentile95(safePartitions);
        List<PartitionLag> top = safePartitions.stream()
                .sorted(Comparator.comparingLong(PartitionLag::lag).reversed()
                        .thenComparing(PartitionLag::topic)
                        .thenComparingInt(PartitionLag::partition))
                .limit(5)
                .toList();
        return new ConsumerLagResult(
                consumerGroup,
                totalLag,
                source,
                safePartitions,
                observedAt,
                p95Lag,
                top,
                summarize(totalLag, safePartitions, p95Lag, top));
    }

    public record PartitionLag(
            String topic,
            int partition,
            long currentOffset,
            long logEndOffset,
            long lag) {}

    private static Double percentile95(List<PartitionLag> partitions) {
        if (partitions == null || partitions.isEmpty()) {
            return null;
        }
        List<Long> lags = partitions.stream()
                .map(PartitionLag::lag)
                .sorted()
                .toList();
        int index = (int) Math.ceil(lags.size() * 0.95d) - 1;
        index = Math.max(0, Math.min(index, lags.size() - 1));
        return lags.get(index).doubleValue();
    }

    private static String summarize(
            long totalLag,
            List<PartitionLag> partitions,
            Double p95Lag,
        List<PartitionLag> top) {
        String p95 = p95Lag == null ? "n/a" : String.format(Locale.ROOT, "%.3f", p95Lag);
        String topSummary = top == null || top.isEmpty()
                ? "none"
                : top.stream()
                        .map(row -> row.topic() + "-" + row.partition() + ":" + row.lag())
                        .toList()
                        .toString();
        return "consumer lag snapshot: total_lag=" + totalLag
                + ", partition_count=" + (partitions == null ? 0 : partitions.size())
                + ", lag p95=" + p95
                + ", top lag partitions=" + topSummary
                + "; offset position snapshot: current committed offsets and log end offsets captured";
    }
}
