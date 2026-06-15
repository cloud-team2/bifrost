package com.bifrost.ops.pipeline.service;

import com.bifrost.ops.auth.jwt.AuthenticatedUser;
import com.bifrost.ops.database.persistence.entity.DatasourceEntity;
import com.bifrost.ops.database.persistence.repository.DatasourceRepository;
import com.bifrost.ops.event.EventLevel;
import com.bifrost.ops.event.EventService;
import com.bifrost.ops.global.common.error.ApiException;
import com.bifrost.ops.global.common.error.ErrorCode;
import com.bifrost.ops.global.common.log.OpsLog;
import com.bifrost.ops.governance.audit.AuditService;
import com.bifrost.ops.pipeline.PipelineLifecycle;
import com.bifrost.ops.pipeline.PipelinePatternCodec;
import com.bifrost.ops.pipeline.dto.ConnectorResponse;
import com.bifrost.ops.pipeline.dto.PipelineCreateRequest;
import com.bifrost.ops.pipeline.dto.PipelineResponse;
import com.bifrost.ops.pipeline.persistence.entity.PipelineEntity;
import com.bifrost.ops.pipeline.persistence.repository.PipelineRepository;
import com.bifrost.ops.provisioning.PipelineProvisioningService;
import com.bifrost.ops.provisioning.dto.ConnectorKind;
import com.bifrost.ops.provisioning.dto.PipelinePattern;
import com.bifrost.ops.provisioning.dto.PipelineProvisionCommand;
import com.bifrost.ops.provisioning.dto.PipelineProvisionResult;
import com.bifrost.ops.provisioning.dto.PipelineResourceRef;
import com.bifrost.ops.provisioning.persistence.entity.ConnectorEntity;
import com.bifrost.ops.provisioning.persistence.repository.ConnectorRepository;
import com.bifrost.ops.workspace.WorkspaceAccessGuard;
import com.bifrost.ops.workspace.persistence.entity.WorkspaceEntity;
import com.bifrost.ops.workspace.persistence.repository.WorkspaceRepository;
import io.micrometer.observation.annotation.Observed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 파이프라인 플랫폼 도메인(#71, FR-003~005).
 *
 * <p>생성 마법사 검증 → {@code creating} 저장 → {@link PipelineProvisioningService} 위임
 * (Strimzi로 KafkaConnector CR apply) → connector 메타데이터 영속화. {@code creating → active}
 * 전이는 {@code KafkaConnectorWatcher}가 실제 connector RUNNING을 보고 {@code PipelineStatusService}
 * (단일 writer)를 통해 만든다.
 */
@Service
public class PipelineService {

    private static final Logger log = LoggerFactory.getLogger(PipelineService.class);

    private final PipelineRepository pipelineRepository;
    private final DatasourceRepository datasourceRepository;
    private final WorkspaceRepository workspaceRepository;
    private final ConnectorRepository connectorRepository;
    private final PipelineProvisioningService provisioningService;
    private final WorkspaceAccessGuard accessGuard;
    private final EventService eventService;
    private final AuditService auditService;
    private final com.bifrost.ops.pipeline.kafka.KafkaResourceCleaner kafkaResourceCleaner;
    private final com.bifrost.ops.database.service.CdcReadinessService cdcReadinessService;
    private final com.bifrost.ops.pipeline.PostgresReplicationSlotCleaner postgresSlotCleaner;
    private final com.bifrost.ops.incident.IncidentService incidentService;

    public PipelineService(PipelineRepository pipelineRepository,
                           DatasourceRepository datasourceRepository,
                           WorkspaceRepository workspaceRepository,
                           ConnectorRepository connectorRepository,
                           PipelineProvisioningService provisioningService,
                           WorkspaceAccessGuard accessGuard,
                           EventService eventService,
                           AuditService auditService,
                           com.bifrost.ops.pipeline.kafka.KafkaResourceCleaner kafkaResourceCleaner,
                           com.bifrost.ops.database.service.CdcReadinessService cdcReadinessService,
                           com.bifrost.ops.pipeline.PostgresReplicationSlotCleaner postgresSlotCleaner,
                           com.bifrost.ops.incident.IncidentService incidentService) {
        this.pipelineRepository = pipelineRepository;
        this.datasourceRepository = datasourceRepository;
        this.workspaceRepository = workspaceRepository;
        this.connectorRepository = connectorRepository;
        this.provisioningService = provisioningService;
        this.accessGuard = accessGuard;
        this.eventService = eventService;
        this.auditService = auditService;
        this.kafkaResourceCleaner = kafkaResourceCleaner;
        this.cdcReadinessService = cdcReadinessService;
        this.postgresSlotCleaner = postgresSlotCleaner;
        this.incidentService = incidentService;
    }

    // ---------- 목록 / 상세 ----------

    public List<PipelineResponse> list(UUID wsId, AuthenticatedUser principal, String statusFilter) {
        accessGuard.requireAccess(wsId, principal);
        List<PipelineEntity> rows = (statusFilter == null || statusFilter.isBlank())
                ? pipelineRepository.findByTenantIdOrderByCreatedAtDesc(wsId)
                : pipelineRepository.findByTenantIdAndStatusOrderByCreatedAtDesc(wsId, parseStatus(statusFilter));
        return rows.stream().map(PipelineResponse::from).toList();
    }

    public PipelineResponse get(UUID wsId, AuthenticatedUser principal, UUID id) {
        accessGuard.requireAccess(wsId, principal);
        return PipelineResponse.from(load(wsId, id));
    }

    /** 파이프라인의 커넥터 목록(#107, 상세 Connector 탭). state/lastError는 watcher가 갱신한 값. */
    public List<ConnectorResponse> listConnectors(UUID wsId, AuthenticatedUser principal, UUID id) {
        accessGuard.requireAccess(wsId, principal);
        load(wsId, id); // 파이프라인이 해당 워크스페이스 소속인지 검증(아니면 404)
        return connectorRepository.findByPipelineId(id).stream()
                .map(ConnectorResponse::from)
                .toList();
    }

    // ---------- 생성 ----------

    @Observed(name = "pipeline.create")
    @Transactional
    public PipelineResponse create(UUID wsId, AuthenticatedUser principal, PipelineCreateRequest req) {
        accessGuard.requireAccess(wsId, principal);
        PipelinePattern pattern = PipelinePatternCodec.parse(req.pattern());

        // 검증(순서대로, 실패 시 VALIDATION_FAILED) — pipeline.md §2
        validatePatternSink(pattern, req.sinkDbId());
        DatasourceEntity source = requireDatasource(wsId, req.sourceDbId(), "source");
        if ("BLOCKED".equalsIgnoreCase(source.getCdcReadinessStatus())) {
            throw validation("source DB의 CDC 준비도가 BLOCKED입니다");
        }
        // #685: 파이프라인 생성 직전 source DB 준비도를 실시간 점검한다.
        // 저장된 cdcReadinessStatus는 이전 점검 결과일 수 있어 replication slot 고갈 같은
        // 순간적 상태 변화를 반영하지 못한다. 점검 실패(일시적 연결 오류 등)는 무시한다.
        try {
            com.bifrost.ops.database.dto.CdcReadinessResponse live =
                    cdcReadinessService.check(wsId, source.getId());
            if (live.overallStatus() == com.bifrost.ops.database.cdc.CdcReadinessStatus.BLOCKED) {
                throw validation("source DB의 CDC 준비도가 BLOCKED입니다 — " +
                        live.checks().stream()
                            .filter(c -> c.status() == com.bifrost.ops.database.cdc.CdcReadinessStatus.BLOCKED)
                            .map(com.bifrost.ops.database.dto.CdcReadinessResponse.CdcCheck::name)
                            .findFirst().orElse("준비도 점검 실패"));
            }
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.debug("CDC 준비도 실시간 점검 실패(저장값으로 대체): dbId={}, cause={}", source.getId(), e.getMessage());
        }
        DatasourceEntity sink = null;
        if (pattern == PipelinePattern.DIRECT) {
            if (req.sinkDbId().equals(req.sourceDbId())) {
                throw validation("source와 sink는 서로 다른 DB여야 합니다");
            }
            sink = requireDatasource(wsId, req.sinkDbId(), "sink");
            // sink 역할 준비도(INSERT 등 쓰기 권한)를 실제로 점검해 sinkReadinessStatus를 채운다(#547).
            // 소스용 cdcReadinessStatus와 별개. 점검 실패(일시적 연결 등)는 무시하고 생성은 계속.
            try {
                cdcReadinessService.checkSink(wsId, sink.getId());
            } catch (Exception ex) {
                log.debug("sink 준비도 점검 실패(무시): dbId={}, cause={}", sink.getId(), ex.getMessage());
            }
        }
        if (pipelineRepository.existsByTenantIdAndName(wsId, req.name())) {
            throw validation("이미 존재하는 파이프라인 이름입니다: " + req.name());
        }
        if (pipelineRepository.existsByTenantIdAndSourceDatasourceIdAndSchemaNameAndTableNameAndPattern(
                wsId, source.getId(), req.schema(), req.table(), pattern)) {
            throw validation("동일한 source·테이블·패턴의 파이프라인이 이미 존재합니다");
        }

        WorkspaceEntity ws = workspaceRepository.findById(wsId)
                .orElseThrow(() -> new ApiException(ErrorCode.WORKSPACE_NOT_FOUND, "워크스페이스를 찾을 수 없습니다"));
        String projectKey = ws.getNamespace();

        PipelineEntity p = new PipelineEntity();
        p.setTenantId(wsId);
        p.setName(req.name());
        p.setPattern(pattern);
        p.setType(pattern == PipelinePattern.FAN_OUT ? "CDC" : "SYNC");
        p.setSourceDatasourceId(source.getId());
        p.setSinkDatasourceId(sink == null ? null : sink.getId());
        p.setSchemaName(req.schema());
        p.setTableName(req.table());
        p.setTables(tablesJson(req.schema(), req.table()));
        p.setTopicName(com.bifrost.ops.provisioning.naming.ConnectorNaming
                .topicName(pattern, projectKey, source.getDbName(), source.getId(), req.schema(), req.table()));
        p.setStatus(PipelineLifecycle.CREATING);
        try {
            p = pipelineRepository.saveAndFlush(p);
        } catch (DataIntegrityViolationException e) {
            throw validation("중복된 파이프라인입니다(이름 또는 source·테이블·패턴)");
        }

        auditService.record(wsId, principal.email(), "PIPELINE_CREATE", "PIPELINE", p.getId(),
                "pattern=" + pattern + ", source=" + source.getId() + ", table=" + req.schema() + "." + req.table());

        // provisioner(Strimzi) 위임. 부분 실패는 result.stage/errorCode로 구분.
        PipelineProvisionResult result = provisioningService.provision(buildCommand(p, projectKey, source, sink, pattern));
        if (result.success()) {
            applyConnectorNames(p, result);
            p.setStatusMessage(null);
            p = pipelineRepository.saveAndFlush(p);
            ensureConnectorRows(p, result);
            eventService.record(wsId, p.getId(), EventLevel.INFO, "PIPELINE_CREATED",
                    "pipeline '" + p.getName() + "' 생성 요청 수락(creating)");
            OpsLog.ok("Pipeline", "파이프라인 생성 요청",
                    "name=" + p.getName() + ", pattern=" + pattern + ", status=creating");
        } else {
            applyConnectorNames(p, result); // 성공한 단계까지의 connector도 기록
            p.setStatus(PipelineLifecycle.ERROR);
            p.setStatusUpdatedAt(Instant.now());
            p.setStatusMessage("provisioning 실패: stage=" + result.stage() + ", code=" + result.errorCode());
            p = pipelineRepository.saveAndFlush(p);
            eventService.record(wsId, p.getId(), EventLevel.ERROR, "PIPELINE_CREATE_FAILED",
                    p.getStatusMessage());
            log.warn("pipeline {} 생성 실패 — {}", p.getId(), p.getStatusMessage());
            OpsLog.fail("Pipeline", "파이프라인 생성 실패",
                    "name=" + p.getName() + ", stage=" + result.stage() + ", code=" + result.errorCode());
        }
        return PipelineResponse.from(p);
    }

    /**
     * provision 결과의 connector를 {@code connectors} 행으로 멱등 생성한다(방어적 보강).
     * provisioner가 이미 행을 만들었으면 skip한다.
     */
    private void ensureConnectorRows(PipelineEntity p, PipelineProvisionResult result) {
        for (PipelineProvisionResult.ConnectorRef ref : result.connectors()) {
            if (connectorRepository.findByCrName(ref.name()).isPresent()) {
                continue;
            }
            ConnectorEntity c = new ConnectorEntity();
            c.setPipelineId(p.getId());
            c.setCrName(ref.name());
            c.setKind(ref.kind());
            c.setConnectorClass(ref.connectorClass());
            c.setTasksMax(ref.kind() == ConnectorKind.SOURCE ? 1 : 3);
            c.setState("UNASSIGNED");
            connectorRepository.save(c);
        }
    }

    // ---------- 생명주기 (skeleton) ----------

    @Transactional
    public PipelineResponse pause(UUID wsId, AuthenticatedUser principal, UUID id) {
        accessGuard.requireAccess(wsId, principal);
        PipelineEntity p = load(wsId, id);
        if (p.getStatus() == PipelineLifecycle.CREATING) {
            throw validation("creating 상태에서는 pause할 수 없습니다");
        }
        // 사용자 lifecycle 액션. 실제 connector state patch는 #78. 여기서는 상태만 전이.
        setStatus(p, PipelineLifecycle.PAUSED);
        p = pipelineRepository.saveAndFlush(p);
        recordUserAction(wsId, principal, p, "PIPELINE_PAUSE", "pipeline '" + p.getName() + "' 일시중지");
        return PipelineResponse.from(p);
    }

    @Transactional
    public PipelineResponse resume(UUID wsId, AuthenticatedUser principal, UUID id) {
        accessGuard.requireAccess(wsId, principal);
        PipelineEntity p = load(wsId, id);
        if (p.getStatus() != PipelineLifecycle.PAUSED) {
            throw validation("paused 상태의 파이프라인만 resume할 수 있습니다");
        }
        setStatus(p, PipelineLifecycle.ACTIVE);
        p = pipelineRepository.saveAndFlush(p);
        recordUserAction(wsId, principal, p, "PIPELINE_RESUME", "pipeline '" + p.getName() + "' 재개");
        return PipelineResponse.from(p);
    }

    /** 데이터플레인 추적 per-pipeline 토글(#438). 의심 파이프라인의 source 커넥터에 tracing SMT on/off. */
    public void setDataplaneTracing(UUID wsId, AuthenticatedUser principal, UUID id, boolean enabled) {
        accessGuard.requireAccess(wsId, principal);
        PipelineEntity p = load(wsId, id);
        provisioningService.setDataplaneTracing(p.getId(), enabled);
    }

    /** 데이터플레인 추적 토글 현재 상태(#438/#565, Tracing 탭 표시용). */
    public boolean isDataplaneTracingEnabled(UUID wsId, AuthenticatedUser principal, UUID id) {
        accessGuard.requireAccess(wsId, principal);
        PipelineEntity p = load(wsId, id);
        return provisioningService.isDataplaneTracingEnabled(p.getId());
    }

    @Transactional
    public void delete(UUID wsId, AuthenticatedUser principal, UUID id, boolean force) {
        accessGuard.requireAccess(wsId, principal);
        PipelineEntity p = load(wsId, id);
        // 정상 삭제: creating(실제 프로비저닝 진행 중)은 in-flight race 방지로 금지. 실패는 error로
        //   전이되므로 삭제 가능(상태 정확성 — #155 watcher/timeout이 보장).
        // 강제 삭제(force): 상태 불문 best-effort 청소 — 상태 전이가 끝내 안 잡히는 경우의 안전판(#155).
        if (!force && p.getStatus() == PipelineLifecycle.CREATING) {
            throw validation("creating 상태에서는 삭제할 수 없습니다 (정리가 필요하면 force=true)");
        }
        // 핵심 보장(#155): 파이프라인 행은 관련 CR이 모두 삭제된 뒤에만 제거된다.
        // CR 정리는 force 여부와 무관하게 반드시 성공해야 하며, 실패하면 예외가 트랜잭션을 롤백시켜
        // 행이 남는다(다음 시도에서 재정리) → 고아 CR이 절대 남지 않는다. force는 상태 가드만 우회한다.
        provisioningService.delete(new PipelineResourceRef(p.getId(), null, connectorNames(p)));
        // Kafka 측 잔재(토픽·sink consumer group) 정리(#200). best-effort — 실패해도 삭제는 진행.
        // CR이 모두 제거된 뒤 호출해야 Debezium source가 토픽을 재생성하지 않는다.
        kafkaResourceCleaner.deleteResources(p.getTopicName(), p.getId(), p.getPattern());
        // PostgreSQL replication slot 정리(#684). best-effort — 실패해도 삭제는 진행.
        // Debezium은 connector CR 삭제 후에도 slot을 자동 drop하지 않으므로 직접 제거한다.
        String projectKey = workspaceRepository.findById(wsId)
                .map(com.bifrost.ops.workspace.persistence.entity.WorkspaceEntity::getNamespace)
                .orElse(null);
        if (projectKey != null) {
            postgresSlotCleaner.dropSlotIfExists(p.getSourceDatasourceId(), projectKey, p.getId());
        }
        connectorRepository.deleteAll(connectorRepository.findByPipelineId(p.getId()));
        pipelineRepository.delete(p);
        // 삭제된 파이프라인의 열린 인시던트를 resolve해 orphan을 방지(#692).
        incidentService.resolveForDeletedPipeline(
                p.getTenantId(), p.getId(), p.getTopicName(), connectorNames(p),
                p.getSinkConnectorName() != null ? "connect-" + p.getSinkConnectorName() : null);
        eventService.record(wsId, null, EventLevel.INFO, "PIPELINE_DELETED",
                "pipeline '" + p.getName() + "' 삭제");
        auditService.record(wsId, principal.email(), "PIPELINE_DELETE", "PIPELINE", id,
                "pipeline '" + p.getName() + "' 삭제");
        OpsLog.ok("Pipeline", "파이프라인 삭제", "name=" + p.getName());
    }

    private void recordUserAction(UUID wsId, AuthenticatedUser principal, PipelineEntity p,
                                  String action, String message) {
        eventService.record(wsId, p.getId(), EventLevel.INFO, action, message);
        auditService.record(wsId, principal.email(), action, "PIPELINE", p.getId(), message);
        OpsLog.ok("Pipeline", message);
    }

    // ---------- 내부 헬퍼 ----------

    private PipelineEntity load(UUID wsId, UUID id) {
        return pipelineRepository.findByIdAndTenantId(id, wsId)
                .orElseThrow(() -> new ApiException(ErrorCode.PIPELINE_NOT_FOUND, "파이프라인을 찾을 수 없습니다"));
    }

    private DatasourceEntity requireDatasource(UUID wsId, UUID dbId, String role) {
        return datasourceRepository.findByIdAndTenantId(dbId, wsId)
                .orElseThrow(() -> validation(role + " DB가 이 워크스페이스에 없습니다: " + dbId));
    }

    private static void validatePatternSink(PipelinePattern pattern, UUID sinkDbId) {
        if (pattern == PipelinePattern.FAN_OUT && sinkDbId != null) {
            throw validation("EDA(fan-out)는 sink를 지정할 수 없습니다");
        }
        if (pattern == PipelinePattern.DIRECT && sinkDbId == null) {
            throw validation("CDC(direct)는 sinkDbId가 필요합니다");
        }
    }

    private PipelineProvisionCommand buildCommand(PipelineEntity p, String projectKey,
                                                  DatasourceEntity source, DatasourceEntity sink,
                                                  PipelinePattern pattern) {
        PipelineProvisionCommand.Endpoint sourceEp = new PipelineProvisionCommand.Endpoint(
                source.getDbType(), source.getHost(), source.getPort(), source.getDbName(), source.getId(),
                p.getSchemaName(), p.getTableName(), source.getSecretRef());
        PipelineProvisionCommand.Endpoint sinkEp = sink == null ? null : new PipelineProvisionCommand.Endpoint(
                sink.getDbType(), sink.getHost(), sink.getPort(), sink.getDbName(), sink.getId(),
                null, null, sink.getSecretRef());
        return new PipelineProvisionCommand(p.getId(), projectKey, pattern, sourceEp, sinkEp);
    }

    private static void applyConnectorNames(PipelineEntity p, PipelineProvisionResult result) {
        for (PipelineProvisionResult.ConnectorRef c : result.connectors()) {
            switch (c.kind()) {
                case SOURCE -> p.setSourceConnectorName(c.name());
                case SINK -> p.setSinkConnectorName(c.name());
            }
        }
    }

    private static List<String> connectorNames(PipelineEntity p) {
        List<String> names = new ArrayList<>();
        if (p.getSourceConnectorName() != null) names.add(p.getSourceConnectorName());
        if (p.getSinkConnectorName() != null) names.add(p.getSinkConnectorName());
        return names;
    }

    private static void setStatus(PipelineEntity p, PipelineLifecycle status) {
        p.setStatus(status);
        p.setStatusUpdatedAt(Instant.now());
    }

    private static String tablesJson(String schema, String table) {
        String qualified = (schema + "." + table).replace("\"", "");
        return "[\"" + qualified + "\"]";
    }

    private PipelineLifecycle parseStatus(String raw) {
        try {
            return PipelineLifecycle.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw validation("지원하지 않는 status 필터: " + raw);
        }
    }

    private static ApiException validation(String message) {
        OpsLog.fail("Pipeline", "검증 실패", message);
        return new ApiException(ErrorCode.VALIDATION_FAILED, message);
    }
}
