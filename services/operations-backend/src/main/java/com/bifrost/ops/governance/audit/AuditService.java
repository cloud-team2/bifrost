package com.bifrost.ops.governance.audit;

import com.bifrost.ops.governance.audit.persistence.entity.AuditEventEntity;
import com.bifrost.ops.governance.audit.persistence.repository.AuditEventRepository;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 감사 로그 기록(#70, 최소 append-only). 비밀값은 detail에 남기지 않는다.
 */
@Service
public class AuditService {

    public static final String ACTOR_SYSTEM = "system";

    private final AuditEventRepository repository;

    public AuditService(AuditEventRepository repository) {
        this.repository = repository;
    }

    public void record(UUID tenantId, String actor, String action, String targetType,
                       UUID targetId, String detail) {
        AuditEventEntity e = new AuditEventEntity();
        e.setTenantId(tenantId);
        e.setActor(actor == null ? ACTOR_SYSTEM : actor);
        e.setAction(action);
        e.setTargetType(targetType);
        e.setTargetId(targetId);
        e.setDetail(detail);
        repository.save(e);
    }

    /** 거버넌스 게이트 통과 mutation용 — policy_decision·approval_id·evidence_id 포함(S3). */
    public void recordMutation(UUID tenantId, String actor, String action, String targetType,
                               UUID targetId, String detail,
                               String policyDecision, UUID approvalId, UUID evidenceId) {
        AuditEventEntity e = new AuditEventEntity();
        e.setTenantId(tenantId);
        e.setActor(actor == null ? ACTOR_SYSTEM : actor);
        e.setAction(action);
        e.setTargetType(targetType);
        e.setTargetId(targetId);
        e.setDetail(detail);
        e.setPolicyDecision(policyDecision);
        e.setApprovalId(approvalId);
        e.setEvidenceId(evidenceId);
        repository.save(e);
    }
}
