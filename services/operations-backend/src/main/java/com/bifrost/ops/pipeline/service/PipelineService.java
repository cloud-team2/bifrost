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
import com.bifrost.ops.pipeline.dto.PipelineCreateRequest;
import com.bifrost.ops.pipeline.dto.PipelineResponse;
import com.bifrost.ops.pipeline.persistence.entity.PipelineEntity;
import com.bifrost.ops.pipeline.persistence.repository.PipelineRepository;
import com.bifrost.ops.pipeline.status.PipelineActivationSimulator;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 파이프라인 플랫폼 도메인(#71, FR-003~005).
 *
 * <p>생성 마법사 검증 → {@code creating} 저장 → {@link PipelineProvisioningService} 위임
 * (mock/real) → connector 메타데이터 영속화. mock mode에서는 트랜잭션 커밋 후 비동기로
 * {@code creating → active} 전이를 만든다(provisioner가 RUNNING을 보고).
 *
 * <p><b>상태 단일 writer(#70) 이관 예정</b>: 현재 active 전이는 이 서비스가 직접 수행하지만,
 * #70에서 {@code PipelineStatusService}로 옮겨 event/audit/SSE와 함께 일원화한다.
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
    private final TaskExecutor activationExecutor;
    /** mock 모드에서만 존재(real 모드는 실제 watcher가 전이). */
    private final ObjectProvider<PipelineActivationSimulator> activationSimulator;

    public PipelineService(PipelineRepository pipelineRepository,
                           DatasourceRepository datasourceRepository,
                           WorkspaceRepository workspaceRepository,
                           ConnectorRepository connectorRepository,
                           PipelineProvisioningService provisioningService,
                           WorkspaceAccessGuard accessGuard,
                           EventService eventService,
                           AuditService auditService,
                           @Qualifier("pipelineActivationExecutor") TaskExecutor activationExecutor,
                           ObjectProvider<PipelineActivationSimulator> activationSimulator) {
        this.pipelineRepository = pipelineRepository;
        this.datasourceRepository = datasourceRepository;
        this.workspaceRepository = workspaceRepository;
        this.connectorRepository = connectorRepository;
        this.provisioningService = provisioningService;
        this.accessGuard = accessGuard;
        this.eventService = eventService;
        this.auditService = auditService;
        this.activationExecutor = activationExecutor;
        this.activationSimulator = activationSimulator;
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

    // ---------- 생성 ----------

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
        DatasourceEntity sink = null;
        if (pattern == PipelinePattern.DIRECT) {
            if (req.sinkDbId().equals(req.sourceDbId())) {
                throw validation("source와 sink는 서로 다른 DB여야 합니다");
            }
            sink = requireDatasource(wsId, req.sinkDbId(), "sink");
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
                .topicName(projectKey, source.getDbName(), req.schema(), req.table()));
        p.setStatus(PipelineLifecycle.CREATING);
        try {
            p = pipelineRepository.saveAndFlush(p);
        } catch (DataIntegrityViolationException e) {
            throw validation("중복된 파이프라인입니다(이름 또는 source·테이블·패턴)");
        }

        auditService.record(wsId, principal.email(), "PIPELINE_CREATE", "PIPELINE", p.getId(),
                "pattern=" + pattern + ", source=" + source.getId() + ", table=" + req.schema() + "." + req.table());

        // provisioner 위임(mock/real). 부분 실패는 result.stage/errorCode로 구분.
        PipelineProvisionResult result = provisioningService.provision(buildCommand(p, projectKey, source, sink, pattern));
        if (result.success()) {
            applyConnectorNames(p, result);
            p.setStatusMessage(null);
            p = pipelineRepository.saveAndFlush(p);
            ensureConnectorRows(p, result);
            eventService.record(wsId, p.getId(), EventLevel.INFO, "PIPELINE_CREATED",
                    "pipeline '" + p.getName() + "' 생성 요청 수락(creating)");
            scheduleActivation(p.getId());
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
     * provision 결과의 connector를 {@code connectors} 행으로 멱등 생성한다.
     * real provisioner는 이미 행을 만들었으므로 skip되고, mock은 여기서 행을 만들어
     * recompute/simulator가 상태를 추적할 수 있게 한다.
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
        // 사용자 lifecycle 액션. mock: 실제 connector state patch는 real(#78). 여기서는 상태만 전이.
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

    @Transactional
    public void delete(UUID wsId, AuthenticatedUser principal, UUID id) {
        accessGuard.requireAccess(wsId, principal);
        PipelineEntity p = load(wsId, id);
        if (p.getStatus() == PipelineLifecycle.CREATING) {
            throw validation("creating 상태에서는 삭제할 수 없습니다");
        }
        provisioningService.delete(new PipelineResourceRef(p.getId(), null, connectorNames(p)));
        connectorRepository.deleteAll(connectorRepository.findByPipelineId(p.getId()));
        pipelineRepository.delete(p);
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

    // ---------- mock active 전이 (단일 writer: PipelineStatusService 경유) ----------

    /**
     * 트랜잭션 커밋 후, mock 모드에 한해 simulator가 connector RUNNING을 통지해
     * {@code creating → active} 전이를 만든다(PipelineStatusService 단일 writer 경유).
     * real 모드는 simulator 빈이 없어 skip되고 실제 watcher가 전이를 만든다. 트랜잭션이 없으면 skip.
     */
    private void scheduleActivation(UUID pipelineId) {
        PipelineActivationSimulator simulator = activationSimulator.getIfAvailable();
        if (simulator == null || !TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                activationExecutor.execute(() -> simulator.simulateRunning(pipelineId));
            }
        });
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
                source.getDbType(), source.getHost(), source.getPort(), source.getDbName(),
                p.getSchemaName(), p.getTableName(), source.getSecretRef());
        PipelineProvisionCommand.Endpoint sinkEp = sink == null ? null : new PipelineProvisionCommand.Endpoint(
                sink.getDbType(), sink.getHost(), sink.getPort(), sink.getDbName(),
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
