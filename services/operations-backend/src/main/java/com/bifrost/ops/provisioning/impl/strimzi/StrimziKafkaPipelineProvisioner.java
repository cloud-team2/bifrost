package com.bifrost.ops.provisioning.impl.strimzi;

import com.bifrost.ops.provisioning.dto.ConnectorKind;
import com.bifrost.ops.provisioning.dto.PipelineProvisionCommand;
import com.bifrost.ops.provisioning.dto.PipelineProvisionResult;
import com.bifrost.ops.provisioning.dto.PipelineProvisionResult.ConnectorRef;
import com.bifrost.ops.provisioning.dto.PipelineProvisionStatus;
import com.bifrost.ops.provisioning.dto.PipelineResourceRef;
import com.bifrost.ops.provisioning.dto.PipelinePattern;
import com.bifrost.ops.provisioning.dto.ProvisionStage;
import com.bifrost.ops.provisioning.naming.ConnectorNaming;
import com.bifrost.ops.provisioning.port.KafkaPipelineProvisioner;
import com.bifrost.ops.secret.DbCredential;
import com.bifrost.ops.secret.SecretStore;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.NonDeletingOperation;
import io.fabric8.kubernetes.client.utils.Serialization;
import io.strimzi.api.kafka.model.connector.KafkaConnector;
import io.strimzi.api.kafka.model.connector.KafkaConnectorStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@link KafkaPipelineProvisioner}의 Fabric8/Strimzi real 구현 (설계 §2 3~6).
 *
 * <p>흐름: SecretStore에서 자격증명을 해석(생성 시점에만) → mapper로 KafkaConnector CR 생성 →
 * Fabric8로 apply(create-or-update). EDA는 Source 1개, CDC는 Source+Sink를 순서대로 apply한다.
 * Strimzi Operator가 CR을 watch해 실제 connector를 등록하고, 상태 반영은 #13 watcher가 담당한다.
 *
 * <p>부분 실패는 예외가 아니라 {@link PipelineProvisionResult}의 {@code stage}/{@code success}로
 * 구분한다(SECRET/SOURCE_CONNECTOR/SINK_CONNECTOR). 자격증명은 mapper 내부에서만 config에 주입하고
 * 로그에는 connector 이름·단계만 남긴다(설계 §1 보안, 비밀값 미노출).
 *
 * <p>구현 스왑(#16): {@code provisioning.mode=real}일 때 활성화한다. 기본(미설정)은 mock-first
 * 원칙에 따라 mock 구현(권세빈)이 담당하므로 이 빈은 생성되지 않는다.
 */
@Component
@ConditionalOnProperty(name = "provisioning.mode", havingValue = "real")
public class StrimziKafkaPipelineProvisioner implements KafkaPipelineProvisioner {

    private static final Logger log = LoggerFactory.getLogger(StrimziKafkaPipelineProvisioner.class);

    private final KubernetesClient k8s;
    private final SecretStore secretStore;
    private final SourceDebeziumConnectorMapper sourceMapper;
    private final JdbcSinkConnectorMapper sinkMapper;
    private final String namespace;
    private final String connectCluster;

    public StrimziKafkaPipelineProvisioner(
            KubernetesClient k8s,
            SecretStore secretStore,
            SourceDebeziumConnectorMapper sourceMapper,
            JdbcSinkConnectorMapper sinkMapper,
            @Value("${kafka-cluster.namespace:platform-kafka}") String namespace,
            @Value("${kafka-connect.cluster:platform-connect}") String connectCluster) {
        this.k8s = k8s;
        this.secretStore = secretStore;
        this.sourceMapper = sourceMapper;
        this.sinkMapper = sinkMapper;
        this.namespace = namespace;
        this.connectCluster = connectCluster;
    }

    @Override
    public PipelineProvisionResult createPipelineResources(PipelineProvisionCommand command) {
        List<ConnectorRef> created = new ArrayList<>();
        String topicPrefix = ConnectorNaming.topicPrefix(
                command.projectKey(), command.source().dbName());

        // 1) Source 자격증명 해석 (생성 시점에만)
        DbCredential sourceCred;
        try {
            sourceCred = secretStore.resolve(command.source().secretRef());
        } catch (RuntimeException e) {
            return fail(command, ProvisionStage.SECRET, created, topicPrefix,
                    "SECRET_RESOLVE_FAILED", "source secret resolve 실패", e);
        }

        // 2) Source(Debezium) KafkaConnector apply
        try {
            KafkaConnector source = sourceMapper.map(command, sourceCred, namespace, connectCluster);
            applyConnector(source);
            created.add(new ConnectorRef(
                    source.getMetadata().getName(), ConnectorKind.SOURCE, source.getSpec().getClassName()));
            log.info("source connector apply 완료: pipeline={}, name={}",
                    command.pipelineId(), source.getMetadata().getName());
        } catch (RuntimeException e) {
            return fail(command, ProvisionStage.SOURCE_CONNECTOR, created, topicPrefix,
                    "SOURCE_CONNECTOR_FAILED", "source connector apply 실패", e);
        }

        // 3) CDC(direct)면 Sink(JDBC) KafkaConnector apply
        if (command.pattern() == PipelinePattern.DIRECT) {
            DbCredential sinkCred;
            try {
                sinkCred = secretStore.resolve(command.sink().secretRef());
            } catch (RuntimeException e) {
                return fail(command, ProvisionStage.SECRET, created, topicPrefix,
                        "SECRET_RESOLVE_FAILED", "sink secret resolve 실패", e);
            }
            try {
                KafkaConnector sink = sinkMapper.map(command, sinkCred, namespace, connectCluster);
                applyConnector(sink);
                created.add(new ConnectorRef(
                        sink.getMetadata().getName(), ConnectorKind.SINK, sink.getSpec().getClassName()));
                log.info("sink connector apply 완료: pipeline={}, name={}",
                        command.pipelineId(), sink.getMetadata().getName());
            } catch (RuntimeException e) {
                return fail(command, ProvisionStage.SINK_CONNECTOR, created, topicPrefix,
                        "SINK_CONNECTOR_FAILED", "sink connector apply 실패", e);
            }
        }

        return PipelineProvisionResult.success(command.pipelineId(), created, topicPrefix);
    }

    @Override
    public PipelineProvisionStatus getConnectorStatus(String projectId, String connectorName) {
        // genericKubernetesResources로 v1 명시 — typed API는 v1beta2로 조회해 404 반환
        GenericKubernetesResource generic = k8s.genericKubernetesResources("kafka.strimzi.io/v1", "KafkaConnector")
                .inNamespace(namespace)
                .withName(connectorName)
                .get();
        if (generic == null) {
            return new PipelineProvisionStatus(connectorName, "UNKNOWN", List.of());
        }
        KafkaConnector cr = Serialization.unmarshal(Serialization.asJson(generic), KafkaConnector.class);
        return new PipelineProvisionStatus(connectorName, readConnectorState(cr), readTasks(cr));
    }

    @Override
    public void deletePipelineResources(PipelineResourceRef resourceRef) {
        String ns = resourceRef.namespace() != null ? resourceRef.namespace() : namespace;
        for (String name : resourceRef.connectorNames()) {
            k8s.genericKubernetesResources("kafka.strimzi.io/v1", "KafkaConnector")
                    .inNamespace(ns).withName(name).delete();
            log.info("connector 삭제 요청: namespace={}, name={}", ns, name);
        }
    }

    /**
     * KafkaConnector CR을 멱등하게 apply(create-or-update)한다.
     *
     * <p>Strimzi Java API 0.45.0의 KafkaConnector 모델이 {@code CustomResource}를 상속하므로
     * {@code getApiVersion()}이 항상 v1beta2를 반환한다. 클러스터(Strimzi 1.0.0)는 v1만 지원하므로
     * JSON 직렬화 후 {@link GenericKubernetesResource}로 변환해 v1 엔드포인트로 apply한다.
     */
    private void applyConnector(KafkaConnector cr) {
        GenericKubernetesResource generic = Serialization.unmarshal(
                Serialization.asJson(cr), GenericKubernetesResource.class);
        k8s.genericKubernetesResources("kafka.strimzi.io/v1", "KafkaConnector")
                .inNamespace(namespace)
                .resource(generic)
                .createOr(NonDeletingOperation::update);
    }

    private PipelineProvisionResult fail(PipelineProvisionCommand command,
                                         ProvisionStage stage,
                                         List<ConnectorRef> created,
                                         String topicPrefix,
                                         String errorCode,
                                         String message,
                                         RuntimeException e) {
        // 비밀값 노출 방지를 위해 예외 메시지 전체가 아니라 단계·코드만 로깅
        log.warn("프로비저닝 실패: pipeline={}, stage={}, code={}",
                command.pipelineId(), stage, errorCode);
        return PipelineProvisionResult.failure(
                command.pipelineId(), stage, created, topicPrefix, errorCode, message);
    }

    /** {@code status.connectorStatus.connector.state} 추출. */
    private String readConnectorState(KafkaConnector cr) {
        KafkaConnectorStatus status = cr.getStatus();
        if (status == null || status.getConnectorStatus() == null) {
            return "UNKNOWN";
        }
        Object connector = status.getConnectorStatus().get("connector");
        if (connector instanceof Map<?, ?> connectorMap) {
            Object state = connectorMap.get("state");
            if (state != null) {
                return state.toString();
            }
        }
        return "UNKNOWN";
    }

    /** {@code status.connectorStatus.tasks[]} 추출. */
    @SuppressWarnings("unchecked")
    private List<PipelineProvisionStatus.TaskState> readTasks(KafkaConnector cr) {
        KafkaConnectorStatus status = cr.getStatus();
        if (status == null || status.getConnectorStatus() == null) {
            return List.of();
        }
        Object tasks = status.getConnectorStatus().get("tasks");
        if (!(tasks instanceof List<?> taskList)) {
            return List.of();
        }
        List<PipelineProvisionStatus.TaskState> result = new ArrayList<>();
        for (Object t : taskList) {
            if (t instanceof Map<?, ?> taskMap) {
                Object id = taskMap.get("id");
                Object state = taskMap.get("state");
                Object trace = taskMap.get("trace");
                result.add(new PipelineProvisionStatus.TaskState(
                        id instanceof Number n ? n.intValue() : -1,
                        state != null ? state.toString() : "UNKNOWN",
                        trace != null ? trace.toString() : null));
            }
        }
        return result;
    }
}
