package com.bifrost.ops.provisioning.dto;

import java.util.Map;

/**
 * Debezium Connector 설정.
 * core의 Inspector가 생성 → orchestrator가 KafkaConnector CRD로 변환.
 * 
 * properties에 ${secrets:secret-name/key} 형식으로 Secret 참조 포함 가능.
 */
public record DebeziumConnectorConfig(
    String connectorClass,
    Map<String, String> properties
) {}
