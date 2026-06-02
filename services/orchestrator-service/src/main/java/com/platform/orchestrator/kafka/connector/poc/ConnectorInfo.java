package com.platform.orchestrator.kafka.connector.poc;

import java.util.List;

/**
 * PoC용 KafkaConnector CR 요약. CR의 metadata/spec/status에서 핵심 필드만 추출한다.
 *
 * @param name           KafkaConnector CR 이름
 * @param namespace      네임스페이스
 * @param connectCluster {@code strimzi.io/cluster} 라벨값(소속 KafkaConnect)
 * @param connectorClass {@code spec.class}
 * @param tasksMax       {@code spec.tasksMax}
 * @param state          {@code status.connectorStatus.connector.state} (없으면 UNKNOWN)
 * @param topics         {@code status.topics}
 */
public record ConnectorInfo(
        String name,
        String namespace,
        String connectCluster,
        String connectorClass,
        Integer tasksMax,
        String state,
        List<String> topics
) {}
