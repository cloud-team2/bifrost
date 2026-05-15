package com.platform.orchestrator.kafka.connector;

import com.platform.common.orchestrator.PipelineCreateRequest;
import com.platform.common.orchestrator.PipelineCreateResponse;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * KafkaConnector CRD 생성/조회/삭제.
 * Strimzi가 이걸 watch해서 Kafka Connect에 실제 Connector 등록.
 */
@Component
public class ConnectorManager {

    private final KubernetesClient k8s;

    public ConnectorManager(KubernetesClient k8s) {
        this.k8s = k8s;
    }

    public PipelineCreateResponse createSourceConnector(PipelineCreateRequest req) {
        // TODO:
        // 1. KafkaTopic CRD 생성 (TopicManager 활용)
        // 2. KafkaConnector CRD 생성 (Debezium config 포함)
        // 3. 즉시 PENDING으로 응답 (실제 RUNNING은 watcher가 감지)
        throw new UnsupportedOperationException("Not implemented yet");
    }

    public String getStatus(UUID pipelineId, String namespace, String connectorName) {
        // TODO: K8s에서 KafkaConnector .status 조회
        throw new UnsupportedOperationException("Not implemented yet");
    }

    public void delete(UUID pipelineId, String namespace, String connectorName) {
        // TODO: KafkaConnector CRD 삭제
        throw new UnsupportedOperationException("Not implemented yet");
    }
}
