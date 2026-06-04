package com.bifrost.ops.workspace.service;

import com.bifrost.ops.auth.jwt.AuthenticatedUser;
import com.bifrost.ops.global.common.error.ApiException;
import com.bifrost.ops.global.common.error.ErrorCode;
import com.bifrost.ops.global.common.log.OpsLog;
import com.bifrost.ops.provisioning.dto.TenantProvisionRequest;
import com.bifrost.ops.provisioning.port.TenantProvisionerPort;
import com.bifrost.ops.workspace.NamespaceSlug;
import com.bifrost.ops.workspace.WorkspaceAccessGuard;
import com.bifrost.ops.workspace.dto.WorkspaceCreateRequest;
import com.bifrost.ops.workspace.dto.WorkspaceResponse;
import com.bifrost.ops.workspace.persistence.entity.WorkspaceEntity;
import com.bifrost.ops.workspace.persistence.repository.WorkspaceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final TenantProvisionerPort tenantProvisioner;
    private final WorkspaceAccessGuard accessGuard;

    public WorkspaceService(WorkspaceRepository workspaceRepository,
                            TenantProvisionerPort tenantProvisioner,
                            WorkspaceAccessGuard accessGuard) {
        this.workspaceRepository = workspaceRepository;
        this.tenantProvisioner = tenantProvisioner;
        this.accessGuard = accessGuard;
    }

    /** 로그인 사용자가 소유한 워크스페이스 목록(생성순). */
    public List<WorkspaceResponse> list(AuthenticatedUser principal) {
        return workspaceRepository.findByOwnerUserIdOrderByCreatedAt(principal.userId()).stream()
                .map(WorkspaceResponse::from)
                .toList();
    }

    /** 워크스페이스 상세. 소유/접근 검증 후 반환. */
    public WorkspaceResponse get(UUID wsId, AuthenticatedUser principal) {
        accessGuard.requireAccess(wsId, principal);
        WorkspaceEntity w = workspaceRepository.findById(wsId)
                .orElseThrow(() -> new ApiException(ErrorCode.WORKSPACE_NOT_FOUND, "워크스페이스를 찾을 수 없습니다"));
        return WorkspaceResponse.from(w);
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
