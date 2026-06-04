package com.bifrost.ops.pipeline.service;

import com.bifrost.ops.auth.jwt.AuthenticatedUser;
import com.bifrost.ops.database.persistence.entity.DatasourceEntity;
import com.bifrost.ops.database.persistence.repository.DatasourceRepository;
import com.bifrost.ops.global.common.error.ApiException;
import com.bifrost.ops.global.common.error.ErrorCode;
import com.bifrost.ops.pipeline.PipelineLifecycle;
import com.bifrost.ops.pipeline.PipelinePatternCodec;
import com.bifrost.ops.pipeline.dto.PipelineCreateRequest;
import com.bifrost.ops.pipeline.dto.PipelineResponse;
import com.bifrost.ops.pipeline.persistence.entity.PipelineEntity;
import com.bifrost.ops.pipeline.persistence.repository.PipelineRepository;
import com.bifrost.ops.provisioning.PipelineProvisioningService;
import com.bifrost.ops.provisioning.dto.PipelinePattern;
import com.bifrost.ops.provisioning.dto.PipelineProvisionCommand;
import com.bifrost.ops.provisioning.dto.PipelineProvisionResult;
import com.bifrost.ops.provisioning.dto.PipelineProvisionStatus;
import com.bifrost.ops.provisioning.dto.PipelineResourceRef;
import com.bifrost.ops.workspace.WorkspaceAccessGuard;
import com.bifrost.ops.workspace.persistence.entity.WorkspaceEntity;
import com.bifrost.ops.workspace.persistence.repository.WorkspaceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private final PipelineProvisioningService provisioningService;
    private final WorkspaceAccessGuard accessGuard;
    private final TaskExecutor activationExecutor;

    public PipelineService(PipelineRepository pipelineRepository,
                           DatasourceRepository datasourceRepository,
                           WorkspaceRepository workspaceRepository,
                           PipelineProvisioningService provisioningService,
                           WorkspaceAccessGuard accessGuard,
                           @Qualifier("pipelineActivationExecutor") TaskExecutor activationExecutor) {
        this.pipelineRepository = pipelineRepository;
        this.datasourceRepository = datasourceRepository;
        this.workspaceRepository = workspaceRepository;
        this.provisioningService = provisioningService;
        this.accessGuard = accessGuard;
        this.activationExecutor = activationExecutor;
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

        // provisioner 위임(mock/real). 부분 실패는 result.stage/errorCode로 구분.
        PipelineProvisionResult result = provisioningService.provision(buildCommand(p, projectKey, source, sink, pattern));
        if (result.success()) {
            applyConnectorNames(p, result);
            p.setStatusMessage(null);
            p = pipelineRepository.saveAndFlush(p);
            scheduleActivation(p.getId(), projectKey);
        } else {
            applyConnectorNames(p, result); // 성공한 단계까지의 connector도 기록
            p.setStatus(PipelineLifecycle.ERROR);
            p.setStatusUpdatedAt(Instant.now());
            p.setStatusMessage("provisioning 실패: stage=" + result.stage() + ", code=" + result.errorCode());
            p = pipelineRepository.saveAndFlush(p);
            log.warn("pipeline {} 생성 실패 — {}", p.getId(), p.getStatusMessage());
        }
        return PipelineResponse.from(p);
    }

    // ---------- 생명주기 (skeleton) ----------

    @Transactional
    public PipelineResponse pause(UUID wsId, AuthenticatedUser principal, UUID id) {
        accessGuard.requireAccess(wsId, principal);
        PipelineEntity p = load(wsId, id);
        if (p.getStatus() == PipelineLifecycle.CREATING) {
            throw validation("creating 상태에서는 pause할 수 없습니다");
        }
        // mock: 실제 connector state patch는 real(#78). 여기서는 상태만 전이.
        setStatus(p, PipelineLifecycle.PAUSED);
        return PipelineResponse.from(pipelineRepository.saveAndFlush(p));
    }

    @Transactional
    public PipelineResponse resume(UUID wsId, AuthenticatedUser principal, UUID id) {
        accessGuard.requireAccess(wsId, principal);
        PipelineEntity p = load(wsId, id);
        if (p.getStatus() != PipelineLifecycle.PAUSED) {
            throw validation("paused 상태의 파이프라인만 resume할 수 있습니다");
        }
        setStatus(p, PipelineLifecycle.ACTIVE);
        return PipelineResponse.from(pipelineRepository.saveAndFlush(p));
    }

    @Transactional
    public void delete(UUID wsId, AuthenticatedUser principal, UUID id) {
        accessGuard.requireAccess(wsId, principal);
        PipelineEntity p = load(wsId, id);
        if (p.getStatus() == PipelineLifecycle.CREATING) {
            throw validation("creating 상태에서는 삭제할 수 없습니다");
        }
        provisioningService.delete(new PipelineResourceRef(p.getId(), null, connectorNames(p)));
        pipelineRepository.delete(p);
    }

    // ---------- mock active 전이 (#70에서 PipelineStatusService로 이관) ----------

    /**
     * provisioner가 모든 connector를 RUNNING으로 보고하면 {@code creating → active}로 전이한다.
     * mock provisioner는 항상 RUNNING이므로 생성 직후 active가 된다.
     */
    public void activateFromProvisioner(UUID pipelineId, String projectKey) {
        PipelineEntity p = pipelineRepository.findById(pipelineId).orElse(null);
        if (p == null || p.getStatus() != PipelineLifecycle.CREATING) {
            return;
        }
        boolean allRunning = isRunning(projectKey, p.getSourceConnectorName())
                && (p.getSinkConnectorName() == null || isRunning(projectKey, p.getSinkConnectorName()));
        if (allRunning) {
            setStatus(p, PipelineLifecycle.ACTIVE);
            pipelineRepository.save(p);
            log.info("[mock] pipeline {} → active 전이", pipelineId);
        }
    }

    private boolean isRunning(String projectKey, String connectorName) {
        if (connectorName == null) {
            return false;
        }
        PipelineProvisionStatus st = provisioningService.status(projectKey, connectorName);
        return "RUNNING".equalsIgnoreCase(st.connectorState());
    }

    /** 트랜잭션 커밋 후 비동기로 active 전이를 시도한다(커밋 전 race 방지). 트랜잭션이 없으면 skip. */
    private void scheduleActivation(UUID pipelineId, String projectKey) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                activationExecutor.execute(() -> activateFromProvisioner(pipelineId, projectKey));
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
        return new ApiException(ErrorCode.VALIDATION_FAILED, message);
    }
}
