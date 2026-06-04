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
}
