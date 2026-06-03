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

    /**
     * 워크스페이스 내 파이프라인에서 <b>source로 사용 중인</b> datasource id 집합.
     * 목록 API의 {@code role} 파생 필터에 쓴다(엔티티에 역할 컬럼 없음 — data-model §3.3).
     *
     * <p>현재 {@code pipelines} 테이블엔 sink datasource 컬럼이 없으므로(데이터모델 divergence)
     * sink 역할은 도출하지 않는다. sink 컬럼 도입 시 별도 쿼리를 추가한다.
     */
    @Query(value = "SELECT DISTINCT source_datasource_id FROM pipelines WHERE tenant_id = :tenantId",
            nativeQuery = true)
    List<UUID> findSourceDatasourceIds(@Param("tenantId") UUID tenantId);
}
