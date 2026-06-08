package com.bifrost.ops.database.persistence.repository;

import com.bifrost.ops.database.persistence.entity.DatasourceEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code datasources} 테이블 접근(#27). {@code tenant_id}는 워크스페이스(=tenant) scope다.
 */
public interface DatasourceRepository extends JpaRepository<DatasourceEntity, UUID> {

    boolean existsByTenantIdAndName(UUID tenantId, String name);

    Optional<DatasourceEntity> findByIdAndTenantId(UUID id, UUID tenantId);

    List<DatasourceEntity> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);

    /** 워크스페이스 내 파이프라인에서 source로 사용 중인 datasource id 집합. */
    @Query(value = "SELECT DISTINCT source_datasource_id FROM pipelines WHERE tenant_id = :tenantId",
            nativeQuery = true)
    List<UUID> findSourceDatasourceIds(@Param("tenantId") UUID tenantId);

    /** 워크스페이스 내 파이프라인에서 sink로 사용 중인 datasource id 집합. */
    @Query(value = "SELECT DISTINCT sink_datasource_id FROM pipelines " +
            "WHERE tenant_id = :tenantId AND sink_datasource_id IS NOT NULL",
            nativeQuery = true)
    List<UUID> findSinkDatasourceIds(@Param("tenantId") UUID tenantId);

    /** 이 datasource를 source로 쓰는 파이프라인 목록(#30, FR-018). */
    @Query(value = "SELECT id, name, type, status FROM pipelines "
            + "WHERE tenant_id = :tenantId AND source_datasource_id = :dbId ORDER BY created_at DESC",
            nativeQuery = true)
    List<PipelineSummaryRow> findPipelinesUsingDatasource(@Param("tenantId") UUID tenantId,
                                                          @Param("dbId") UUID dbId);
}
