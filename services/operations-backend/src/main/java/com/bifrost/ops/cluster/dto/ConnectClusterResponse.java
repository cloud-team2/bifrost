package com.bifrost.ops.cluster.dto;

import java.util.List;
import java.util.Map;

/**
 * Kafka Connect 클러스터 개요(Cluster 화면 KafkaConnect 탭, #213).
 * worker는 k8s 파드 + Prometheus(JVM/CPU), connectors는 ConnectorEntity, plugins·config는 KafkaConnect CR.
 */
public record ConnectClusterResponse(
        List<Worker> workers,
        List<ConnectorRow> connectors,
        List<Plugin> plugins,
        Map<String, String> config) {

    /**
     * @param name 파드명, host 파드 IP, state 파드 phase(Running 등)
     * @param heapUsedBytes/heapMaxBytes JVM heap, cpuPct 컨테이너 CPU(%), gcSeconds 최근 GC 누적(초)
     * @param version Connect/Kafka 버전
     */
    public record Worker(
            String name,
            String host,
            String state,
            Long heapUsedBytes,
            Long heapMaxBytes,
            Double cpuPct,
            Double gcSeconds,
            String version) {
    }

    /**
     * @param name 커넥터 CR 이름, kind SOURCE|SINK, status 상태, pipeline 소속 파이프라인명, tasks tasksMax
     */
    public record ConnectorRow(
            String name,
            String kind,
            String status,
            String pipeline,
            int tasks) {
    }

    /** @param className 플러그인 클래스, type Source|Sink|Converter 등, version 플러그인 버전 */
    public record Plugin(String className, String type, String version) {
    }
}
