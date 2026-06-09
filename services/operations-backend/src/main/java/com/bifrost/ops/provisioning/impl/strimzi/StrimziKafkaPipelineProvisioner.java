package com.bifrost.ops.provisioning.impl.strimzi;

import com.bifrost.ops.provisioning.dto.ConnectorKind;
import com.bifrost.ops.provisioning.dto.PipelineProvisionCommand;
import com.bifrost.ops.provisioning.dto.PipelineProvisionResult;
import com.bifrost.ops.provisioning.dto.PipelineProvisionResult.ConnectorRef;
import com.bifrost.ops.provisioning.dto.PipelineProvisionStatus;
import com.bifrost.ops.provisioning.dto.PipelineResourceRef;
import com.bifrost.ops.provisioning.dto.PipelinePattern;
import com.bifrost.ops.provisioning.dto.ProvisionErrorCode;
import com.bifrost.ops.provisioning.naming.ConnectorNaming;
import com.bifrost.ops.provisioning.persistence.entity.ConnectorEntity;
import com.bifrost.ops.provisioning.persistence.repository.ConnectorRepository;
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
 * <p>{@link KafkaPipelineProvisioner}의 단일 구현이다. 파이프라인 생성은 Strimzi(K8s)를 통해
 * 실제 KafkaConnector CR을 만든다 — 로컬에서는 kind+Strimzi가 필요하다.
 */
@Component
public class StrimziKafkaPipelineProvisioner implements KafkaPipelineProvisioner {

    private static final Logger log = LoggerFactory.getLogger(StrimziKafkaPipelineProvisioner.class);

    private final KubernetesClient k8s;
    private final SecretStore secretStore;
    private final SourceDebeziumConnectorMapper sourceMapper;
    private final JdbcSinkConnectorMapper sinkMapper;
    private final ConnectorRepository connectorRepository;
    private final String namespace;
    private final String connectCluster;

    public StrimziKafkaPipelineProvisioner(
            KubernetesClient k8s,
            SecretStore secretStore,
            SourceDebeziumConnectorMapper sourceMapper,
            JdbcSinkConnectorMapper sinkMapper,
            ConnectorRepository connectorRepository,
            @Value("${kafka-cluster.namespace:platform-kafka}") String namespace,
            @Value("${kafka-connect.cluster:platform-connect}") String connectCluster) {
        this.k8s = k8s;
        this.secretStore = secretStore;
        this.sourceMapper = sourceMapper;
        this.sinkMapper = sinkMapper;
        this.connectorRepository = connectorRepository;
        this.namespace = namespace;
        this.connectCluster = connectCluster;
    }

    @Override
    public PipelineProvisionResult createPipelineResources(PipelineProvisionCommand command) {
        List<ConnectorRef> created = new ArrayList<>();
        String topicPrefix = ConnectorNaming.topicPrefix(
                command.projectKey(), command.source().dbName(), command.source().datasourceId());

        // 1) Source 자격증명 해석 (생성 시점에만)
        DbCredential sourceCred;
        try {
            sourceCred = secretStore.resolve(command.source().secretRef());
        } catch (RuntimeException e) {
            return fail(command, ProvisionErrorCode.SECRET_RESOLVE_FAILED, created, topicPrefix,
                    "source secret resolve 실패", e);
        }

        // 2) Source(Debezium) KafkaConnector apply
        try {
            KafkaConnector source = sourceMapper.map(command, sourceCred, namespace, connectCluster);
            applyConnector(source);
            created.add(new ConnectorRef(
                    source.getMetadata().getName(), ConnectorKind.SOURCE, source.getSpec().getClassName()));
            persistConnector(command.pipelineId(), source, ConnectorKind.SOURCE);
            log.info("source connector apply 완료: pipeline={}, name={}",
                    command.pipelineId(), source.getMetadata().getName());
        } catch (RuntimeException e) {
            return fail(command, ProvisionErrorCode.SOURCE_CONNECTOR_FAILED, created, topicPrefix,
                    "source connector apply 실패", e);
        }

        // 3) CDC(direct)면 Sink(JDBC) KafkaConnector apply
        if (command.pattern() == PipelinePattern.DIRECT) {
            DbCredential sinkCred;
            try {
                sinkCred = secretStore.resolve(command.sink().secretRef());
            } catch (RuntimeException e) {
                return fail(command, ProvisionErrorCode.SECRET_RESOLVE_FAILED, created, topicPrefix,
                        "sink secret resolve 실패", e);
            }
            try {
                KafkaConnector sink = sinkMapper.map(command, sinkCred, namespace, connectCluster);
                applyConnector(sink);
                created.add(new ConnectorRef(
                        sink.getMetadata().getName(), ConnectorKind.SINK, sink.getSpec().getClassName()));
                persistConnector(command.pipelineId(), sink, ConnectorKind.SINK);
                log.info("sink connector apply 완료: pipeline={}, name={}",
                        command.pipelineId(), sink.getMetadata().getName());
            } catch (RuntimeException e) {
                return fail(command, ProvisionErrorCode.SINK_CONNECTOR_FAILED, created, topicPrefix,
                        "sink connector apply 실패", e);
            }
        }

        return PipelineProvisionResult.success(command.pipelineId(), created, topicPrefix);
    }

    @Override
    public PipelineProvisionStatus getConnectorStatus(String projectId, String connectorName) {
        // typed API는 v1beta2 URL로 조회해 클러스터가 404를 반환하므로 generic resource로 v1 명시
        GenericKubernetesResource template = buildConnectorTemplate(connectorName);
        GenericKubernetesResource generic = k8s.resource(template).inNamespace(namespace).get();
        if (generic == null) {
            return new PipelineProvisionStatus(connectorName, "UNKNOWN", List.of());
        }
        KafkaConnector cr = Serialization.unmarshal(Serialization.asJson(generic), KafkaConnector.class);
        return new PipelineProvisionStatus(connectorName, readConnectorState(cr), readTasks(cr));
    }

    @Override
    public void deletePipelineResources(PipelineResourceRef resourceRef) {
        String ns = resourceRef.namespace() != null ? resourceRef.namespace() : namespace;
        java.util.UUID pid = resourceRef.pipelineId();

        // 삭제 대상: 저장된 이름 + pipelineId 기반 결정적 이름({pid}-source/-sink).
        // 엔티티에 이름이 누락/불일치(프로비저닝 부분 실패 등)해도 결정적 이름으로 표준 CR을 보장 삭제한다.
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>(resourceRef.connectorNames());
        if (pid != null) {
            names.add(pid + "-source");
            names.add(pid + "-sink");
        }
        for (String name : names) {
            // 없는 CR은 no-op. k8s 접근 실패는 예외를 던져 호출부 트랜잭션을 롤백시킨다(고아 CR 방지).
            k8s.resource(buildConnectorTemplate(name)).inNamespace(ns).delete();
            log.info("connector 삭제 요청: namespace={}, name={}", ns, name);
        }

        // 고아 방지 sweep(#155): pipelineId 접두사를 가진 KafkaConnector CR을 전수 조회해 남은 것도 삭제.
        if (pid != null) {
            sweepOrphanConnectors(ns, pid + "-");
        }
    }

    /** {@code prefix}로 시작하는 KafkaConnector CR을 전수 삭제(이름 기반 삭제 후 잔여 보강). */
    private void sweepOrphanConnectors(String ns, String prefix) {
        try {
            var list = k8s.genericKubernetesResources("kafka.strimzi.io/v1", "KafkaConnector")
                    .inNamespace(ns)
                    .withLabel("strimzi.io/cluster", connectCluster)
                    .list();
            for (GenericKubernetesResource cr : list.getItems()) {
                String name = cr.getMetadata() != null ? cr.getMetadata().getName() : null;
                if (name != null && name.startsWith(prefix)) {
                    k8s.resource(buildConnectorTemplate(name)).inNamespace(ns).delete();
                    log.info("connector 고아 sweep 삭제: namespace={}, name={}", ns, name);
                }
            }
        } catch (RuntimeException e) {
            // 목록 조회 실패 시: 이름 기반(결정적) 삭제는 이미 끝났으므로 표준 CR은 정리된 상태다.
            log.warn("connector 고아 sweep 조회 실패(결정적 이름 삭제는 완료): prefix={}, cause={}",
                    prefix, e.getMessage());
        }
    }

    /** 이름만 있는 v1 KafkaConnector GenericKubernetesResource(get/delete용 key 객체). */
    private GenericKubernetesResource buildConnectorTemplate(String name) {
        GenericKubernetesResource template = new GenericKubernetesResource();
        template.setApiVersion("kafka.strimzi.io/v1");
        template.setKind("KafkaConnector");
        io.fabric8.kubernetes.api.model.ObjectMeta meta = new io.fabric8.kubernetes.api.model.ObjectMeta();
        meta.setName(name);
        meta.setNamespace(namespace);
        template.setMetadata(meta);
        return template;
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
        // Strimzi 1.0.0 클러스터는 v1만 지원하므로 apiVersion을 명시적으로 v1로 교체한다.
        // k8s.resource(HasMetadata) 는 resource의 apiVersion 필드를 URL 결정에 사용하므로
        // genericKubernetesResources 와 달리 Fabric8 mock 서버에서도 동작한다.
        generic.setApiVersion("kafka.strimzi.io/v1");
        k8s.resource(generic).inNamespace(namespace).createOr(NonDeletingOperation::update);
    }

    /**
     * connectors 메타 행을 기록한다(watcher 상태 반영의 대상, #43/#46). cr_name 기준 upsert.
     * DB 오류가 CR apply 성공을 가리지 않도록 best-effort로 처리한다(상태는 watcher가 재반영).
     */
    private void persistConnector(java.util.UUID pipelineId, KafkaConnector cr, ConnectorKind kind) {
        String name = cr.getMetadata().getName();
        try {
            ConnectorEntity entity = connectorRepository.findByCrName(name)
                    .orElseGet(ConnectorEntity::new);
            entity.setPipelineId(pipelineId);
            entity.setCrName(name);
            entity.setKind(kind);
            entity.setConnectorClass(cr.getSpec().getClassName());
            Integer tasksMax = cr.getSpec().getTasksMax();
            entity.setTasksMax(tasksMax != null ? tasksMax : (kind == ConnectorKind.SOURCE ? 1 : 3));
            connectorRepository.save(entity);
        } catch (RuntimeException e) {
            log.warn("connector 메타 영속화 실패(무시): name={}, cause={}",
                    name, e.getClass().getSimpleName());
        }
    }

    private PipelineProvisionResult fail(PipelineProvisionCommand command,
                                         ProvisionErrorCode errorCode,
                                         List<ConnectorRef> created,
                                         String topicPrefix,
                                         String message,
                                         RuntimeException e) {
        // 비밀값 노출 방지를 위해 예외 메시지 전체가 아니라 단계·코드·예외 타입만 로깅
        log.warn("프로비저닝 실패: pipeline={}, stage={}, code={}, createdSoFar={}, cause={}",
                command.pipelineId(), errorCode.stage(), errorCode.code(), created.size(),
                e.getClass().getSimpleName());
        return PipelineProvisionResult.failure(
                command.pipelineId(), errorCode, created, topicPrefix, message);
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
