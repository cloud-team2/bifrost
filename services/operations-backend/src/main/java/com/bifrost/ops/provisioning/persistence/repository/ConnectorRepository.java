package com.bifrost.ops.provisioning.persistence.repository;

import com.bifrost.ops.provisioning.persistence.entity.ConnectorEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * connector 메타데이터 저장소(설계 §4 3.5).
 *
 * <p>provisioner(#12)가 생성 후 행을 기록하고, watcher 상태 adapter(#46)가 {@code cr_name}으로
 * 찾아 {@code state}/{@code lastError}/{@code updatedAt}를 갱신한다.
 */
public interface ConnectorRepository extends JpaRepository<ConnectorEntity, UUID> {

    Optional<ConnectorEntity> findByCrName(String crName);

    List<ConnectorEntity> findByPipelineId(UUID pipelineId);

    @Query(value = """
            SELECT c.*
            FROM connectors c
            JOIN pipelines p ON p.id = c.pipeline_id
            WHERE p.tenant_id = :tenantId
            ORDER BY c.cr_name
            """, nativeQuery = true)
    List<ConnectorEntity> findByTenantIdOrderByCrName(@Param("tenantId") UUID tenantId);

    @Query(value = """
            SELECT count(*)
            FROM connectors c
            JOIN pipelines p ON p.id = c.pipeline_id
            WHERE p.tenant_id = :tenantId
            """, nativeQuery = true)
    long countByTenantId(@Param("tenantId") UUID tenantId);

    @Query(value = """
            SELECT count(*)
            FROM connectors c
            JOIN pipelines p ON p.id = c.pipeline_id
            WHERE p.tenant_id = :tenantId
              AND c.state IN (:failedState, :partiallyFailedState)
            """, nativeQuery = true)
    long countByTenantIdAndStateIn(@Param("tenantId") UUID tenantId,
                                   @Param("failedState") String failedState,
                                   @Param("partiallyFailedState") String partiallyFailedState);
}
