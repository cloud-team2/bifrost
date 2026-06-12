package com.bifrost.ops.workspace.service;

import com.bifrost.ops.auth.jwt.AuthenticatedUser;
import com.bifrost.ops.auth.persistence.repository.UserRepository;
import com.bifrost.ops.database.service.DatabaseService;
import com.bifrost.ops.global.common.error.ApiException;
import com.bifrost.ops.global.common.error.ErrorCode;
import com.bifrost.ops.global.common.log.OpsLog;
import com.bifrost.ops.pipeline.PipelineLifecycle;
import com.bifrost.ops.pipeline.persistence.repository.PipelineRepository;
import com.bifrost.ops.provisioning.dto.TenantProvisionRequest;
import com.bifrost.ops.provisioning.port.TenantProvisionerPort;
import com.bifrost.ops.workspace.NamespaceSlug;
import com.bifrost.ops.workspace.Role;
import com.bifrost.ops.workspace.WorkspaceAccessGuard;
import com.bifrost.ops.workspace.dto.WorkspaceCreateRequest;
import com.bifrost.ops.workspace.dto.WorkspaceResponse;
import com.bifrost.ops.workspace.dto.WorkspaceUpdateRequest;
import com.bifrost.ops.workspace.persistence.entity.ProjectMemberEntity;
import com.bifrost.ops.workspace.persistence.entity.WorkspaceEntity;
import com.bifrost.ops.workspace.persistence.repository.ProjectMemberRepository;
import com.bifrost.ops.workspace.persistence.repository.WorkspaceCleanupRepository;
import com.bifrost.ops.workspace.persistence.repository.WorkspaceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.UUID;

/**
 * 워크스페이스 플랫폼 도메인(#72, FR-002).
 *
 * <p>소유 기반 다중 워크스페이스: 사용자는 여러 워크스페이스를 소유할 수 있고,
 * 생성 시 namespace/projectKey 슬러그를 만들고 KafkaUser/ACL 프로비저닝을 트리거한다.
 * scope 인가는 {@link WorkspaceAccessGuard} 단일 출처를 사용한다.
 */
@Service
public class WorkspaceService {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceService.class);

    private final WorkspaceRepository workspaceRepository;
    private final ProjectMemberRepository memberRepository;
    private final PipelineRepository pipelineRepository;
    private final TenantProvisionerPort tenantProvisioner;
    private final WorkspaceAccessGuard accessGuard;
    private final UserRepository userRepository;
    private final DatabaseService databaseService;
    private final WorkspaceCleanupRepository cleanupRepository;

    public WorkspaceService(WorkspaceRepository workspaceRepository,
                            ProjectMemberRepository memberRepository,
                            PipelineRepository pipelineRepository,
                            TenantProvisionerPort tenantProvisioner,
                            WorkspaceAccessGuard accessGuard,
                            UserRepository userRepository,
                            DatabaseService databaseService,
                            WorkspaceCleanupRepository cleanupRepository) {
        this.workspaceRepository = workspaceRepository;
        this.memberRepository = memberRepository;
        this.pipelineRepository = pipelineRepository;
        this.tenantProvisioner = tenantProvisioner;
        this.accessGuard = accessGuard;
        this.userRepository = userRepository;
        this.databaseService = databaseService;
        this.cleanupRepository = cleanupRepository;
    }

    /** 로그인 사용자가 소유한 워크스페이스 목록(생성순). 카드 요약용 파이프라인 카운트 포함(#105). */
    public List<WorkspaceResponse> list(AuthenticatedUser principal) {
        List<UUID> workspaceIds = memberRepository.findByIdUserIdOrderByJoinedAtAsc(principal.userId()).stream()
                .map(ProjectMemberEntity::getWorkspaceId)
                .toList();
        List<WorkspaceEntity> workspaces = workspaceIds.isEmpty()
                ? workspaceRepository.findByOwnerUserIdOrderByCreatedAt(principal.userId())
                : workspaceRepository.findByIdInOrderByCreatedAt(workspaceIds);
        return workspaces.stream()
                .map(this::withCounts)
                .toList();
    }

    /** 워크스페이스 상세. 소유/접근 검증 후 반환. */
    public WorkspaceResponse get(UUID wsId, AuthenticatedUser principal) {
        accessGuard.requireAccess(wsId, principal);
        WorkspaceEntity w = workspaceRepository.findById(wsId)
                .orElseThrow(() -> new ApiException(ErrorCode.WORKSPACE_NOT_FOUND, "워크스페이스를 찾을 수 없습니다"));
        return withCounts(w);
    }

    /** 워크스페이스 엔티티에 파이프라인 전체/active 카운트를 채워 응답으로 변환한다(#105). */
    private WorkspaceResponse withCounts(WorkspaceEntity w) {
        long total = pipelineRepository.countByTenantId(w.getId());
        long active = pipelineRepository.countByTenantIdAndStatus(w.getId(), PipelineLifecycle.ACTIVE);
        return WorkspaceResponse.from(w, total, active);
    }

    /** 워크스페이스 생성: 슬러그 생성 → 소유자 연결 → 저장 → KafkaUser/ACL 프로비저닝 트리거. */
    @Transactional
    public WorkspaceResponse create(AuthenticatedUser principal, WorkspaceCreateRequest req) {
        if (workspaceRepository.existsByName(req.name())) {
            OpsLog.fail("Project", "신규 프로젝트 생성 실패", "name=" + req.name() + ", reason=이미 사용 중인 이름");
            throw new ApiException(ErrorCode.WORKSPACE_NAME_CONFLICT, "이미 사용 중인 워크스페이스 이름");
        }

        String namespace = NamespaceSlug.generate(req.name(), workspaceRepository::existsByNamespace);

        WorkspaceEntity workspace = new WorkspaceEntity();
        workspace.setName(req.name());
        workspace.setNamespace(namespace);
        workspace.setOwnerUserId(principal.userId());
        workspace.setStatus(WorkspaceEntity.Status.PROVISIONING);

        try {
            workspace = workspaceRepository.saveAndFlush(workspace);
            memberRepository.saveAndFlush(new ProjectMemberEntity(workspace.getId(), principal.userId(), Role.OWNER));
        } catch (DataIntegrityViolationException e) {
            ApiException mapped = mapConflict(e);
            OpsLog.fail("Project", "신규 프로젝트 생성 실패", "name=" + req.name() + ", reason=" + mapped.getMessage());
            throw mapped;
        }

        triggerProvisioning(workspace);
        OpsLog.ok("Project", "신규 프로젝트 생성",
                "name=" + workspace.getName() + ", namespace=" + workspace.getNamespace()
                        + ", wsId=" + workspace.getId());
        return WorkspaceResponse.from(workspace);
    }

    @Transactional
    public WorkspaceResponse update(UUID wsId, AuthenticatedUser principal, WorkspaceUpdateRequest req) {
        requireManager(wsId, principal);
        WorkspaceEntity workspace = workspaceRepository.findById(wsId)
                .orElseThrow(() -> new ApiException(ErrorCode.WORKSPACE_NOT_FOUND, "워크스페이스를 찾을 수 없습니다"));
        if (req.name() != null && !req.name().isBlank()) {
            String name = req.name().trim();
            if (!name.equals(workspace.getName()) && workspaceRepository.existsByName(name)) {
                throw new ApiException(ErrorCode.WORKSPACE_NAME_CONFLICT, "이미 사용 중인 워크스페이스 이름");
            }
            workspace.setName(name);
        }
        if (req.timezone() != null) {
            workspace.setTimezone(req.timezone().isBlank() ? null : req.timezone().trim());
        }
        return withCounts(workspaceRepository.saveAndFlush(workspace));
    }

    /**
     * 워크스페이스 삭제. 기존 delete 패턴과 같이 hard delete이며, 파이프라인과 home workspace는 먼저 정리해야 한다.
     */
    @Transactional
    public void delete(UUID wsId, AuthenticatedUser principal) {
        requireManager(wsId, principal);
        WorkspaceEntity workspace = workspaceRepository.findById(wsId)
                .orElseThrow(() -> new ApiException(ErrorCode.WORKSPACE_NOT_FOUND, "워크스페이스를 찾을 수 없습니다"));

        long activePipelines = pipelineRepository.countByTenantIdAndStatus(wsId, PipelineLifecycle.ACTIVE);
        if (activePipelines > 0) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "활성 파이프라인이 있는 워크스페이스는 삭제할 수 없습니다. 파이프라인을 먼저 삭제하세요.");
        }
        if (pipelineRepository.countByTenantId(wsId) > 0) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "파이프라인이 있는 워크스페이스는 삭제할 수 없습니다. 파이프라인을 먼저 삭제하세요.");
        }
        if (wsId.equals(principal.tenantId()) || userRepository.existsByTenantId(wsId)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "사용자의 home 워크스페이스는 삭제할 수 없습니다.");
        }

        databaseService.deleteAllForWorkspace(wsId);
        cleanupRepository.deleteWorkspaceMetadata(wsId);
        deprovisionAfterCommit(wsId);
        OpsLog.ok("Project", "프로젝트 삭제",
                "name=" + workspace.getName() + ", namespace=" + workspace.getNamespace() + ", wsId=" + wsId);
    }

    private void deprovisionAfterCommit(UUID wsId) {
        Runnable task = () -> {
            try {
                tenantProvisioner.deprovision(wsId);
            } catch (Exception e) {
                log.warn("워크스페이스 외부 리소스 정리 실패 — workspace {}", wsId, e);
            }
        };
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            task.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                task.run();
            }
        });
    }

    private void requireManager(UUID wsId, AuthenticatedUser principal) {
        boolean hasRole = memberRepository.existsByIdWorkspaceIdAndIdUserIdAndRoleIn(
                wsId, principal.userId(), List.of(Role.OWNER, Role.ADMIN));
        if (hasRole || workspaceRepository.existsByIdAndOwnerUserId(wsId, principal.userId())) {
            return;
        }
        throw new ApiException(ErrorCode.WORKSPACE_FORBIDDEN, "워크스페이스 관리 권한이 없습니다");
    }

    private ApiException mapConflict(DataIntegrityViolationException e) {
        String detail = e.getMostSpecificCause() != null ? e.getMostSpecificCause().getMessage() : "";
        String normalized = detail.toLowerCase();
        if (normalized.contains("namespace")) {
            return new ApiException(ErrorCode.WORKSPACE_NAMESPACE_CONFLICT, "이미 사용 중인 namespace");
        }
        if (normalized.contains("name")) {
            return new ApiException(ErrorCode.WORKSPACE_NAME_CONFLICT, "이미 사용 중인 워크스페이스 이름");
        }
        log.warn("워크스페이스 생성 중 unique 제약 충돌 — 매핑 실패: {}", detail);
        return new ApiException(ErrorCode.WORKSPACE_NAME_CONFLICT, "이미 사용 중인 값이 있습니다");
    }

    /** KafkaUser/ACL 프로비저닝 트리거. 실패해도 워크스페이스는 PROVISIONING 상태로 남는다. */
    private void triggerProvisioning(WorkspaceEntity workspace) {
        TenantProvisionRequest req = new TenantProvisionRequest(
                workspace.getId(),
                workspace.getNamespace(),
                TenantProvisionRequest.ResourceQuotaSpec.defaultQuota()
        );
        try {
            tenantProvisioner.provision(req);
        } catch (UnsupportedOperationException e) {
            log.warn("TenantProvisioner 미구현 — workspace {} 는 PROVISIONING 상태로 남는다", workspace.getId());
        } catch (Exception e) {
            log.error("TenantProvisioner 호출 실패 — workspace {}", workspace.getId(), e);
        }
    }
}
