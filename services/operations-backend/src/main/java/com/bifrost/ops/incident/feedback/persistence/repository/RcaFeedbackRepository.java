package com.bifrost.ops.incident.feedback.persistence.repository;

import com.bifrost.ops.incident.feedback.persistence.entity.RcaFeedbackEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** #964 RCA 피드백 저장소. 인시던트별 조회 + 워크스페이스 gold set 조회. */
public interface RcaFeedbackRepository extends JpaRepository<RcaFeedbackEntity, UUID> {

    List<RcaFeedbackEntity> findByTenantIdAndIncidentIdOrderByCreatedAtDesc(UUID tenantId, UUID incidentId);

    List<RcaFeedbackEntity> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);
}
