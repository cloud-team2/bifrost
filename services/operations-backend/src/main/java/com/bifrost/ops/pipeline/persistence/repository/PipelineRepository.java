package com.bifrost.ops.pipeline.persistence.repository;

import com.bifrost.ops.pipeline.PipelineLifecycle;
import com.bifrost.ops.pipeline.persistence.entity.PipelineEntity;
import com.bifrost.ops.provisioning.dto.PipelinePattern;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code pipelines} 테이블 접근(#71). {@code tenant_id}가 워크스페이스 scope다.
 */
public interface PipelineRepository extends JpaRepository<PipelineEntity, UUID> {

    boolean existsByTenantIdAndName(UUID tenantId, String name);

    Optional<PipelineEntity> findByIdAndTenantId(UUID id, UUID tenantId);

    List<PipelineEntity> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);

    List<PipelineEntity> findByTenantIdAndStatusOrderByCreatedAtDesc(UUID tenantId, PipelineLifecycle status);

    /** 워크스페이스 요약 카운트(프로젝트 목록 카드용, #105). */
    long countByTenantId(UUID tenantId);

    long countByTenantIdAndStatus(UUID tenantId, PipelineLifecycle status);

    /** offset 스냅샷 수집용: null이 아닌 모든 topic 이름(중복 제거). */
    @Query("SELECT DISTINCT p.topicName FROM PipelineEntity p WHERE p.topicName IS NOT NULL")
    List<String> findAllActiveTopics();

    /** 하나의 pipeline = 하나의 topic/table: 동일 source+schema+table+pattern 중복 차단용. */
    boolean existsByTenantIdAndSourceDatasourceIdAndSchemaNameAndTableNameAndPattern(
            UUID tenantId, UUID sourceDatasourceId, String schemaName, String tableName, PipelinePattern pattern);
}
