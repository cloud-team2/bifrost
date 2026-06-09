package com.bifrost.ops.cluster.dto;

import java.util.List;

/**
 * Kafka 클러스터 개요(Cluster 화면 Brokers 탭, #213).
 * 구조·파티션 건강은 AdminClient, per-broker 리소스(cpu/disk/net)는 Prometheus(node-exporter/cAdvisor).
 *
 * @param controllerId 활성 컨트롤러 broker id(-1=미상)
 * @param brokerCount  브로커 수
 * @param totalPartitions 전체 파티션 수(복제본 제외, leader 기준)
 * @param underReplicated ISR < replicas 인 파티션 수
 * @param offlinePartitions leader 없는 파티션 수
 * @param brokers 브로커별 상세
 */
public record KafkaClusterResponse(
        int controllerId,
        int brokerCount,
        long totalPartitions,
        long underReplicated,
        long offlinePartitions,
        List<BrokerInfo> brokers) {

    /**
     * @param id broker id
     * @param host 광고 호스트
     * @param port 광고 포트
     * @param controller 활성 컨트롤러 여부
     * @param leaderPartitions 이 브로커가 leader인 파티션 수
     * @param logDirBytes Kafka 로그 디렉토리 사용 바이트(데이터 크기)
     * @param cpuPct 컨테이너 CPU 사용률(%, null=메트릭 없음)
     * @param diskUsedPct 노드 디스크 사용률(%, null=메트릭 없음)
     * @param netInBytesPerSec 초당 수신 바이트(null=메트릭 없음)
     * @param netOutBytesPerSec 초당 송신 바이트(null=메트릭 없음)
     * @param status healthy|warning|error (파드/메트릭 기반)
     */
    public record BrokerInfo(
            int id,
            String host,
            int port,
            boolean controller,
            long leaderPartitions,
            long logDirBytes,
            Double cpuPct,
            Double diskUsedPct,
            Double netInBytesPerSec,
            Double netOutBytesPerSec,
            String status) {
    }
}
