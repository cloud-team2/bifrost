package com.bifrost.ops.database.dto;

import com.bifrost.ops.database.persistence.entity.DatasourceEntity;

import java.time.Instant;
import java.util.List;

/**
 * Database 목록/상세 응답(#27). 자격증명은 절대 노출하지 않는다 — {@code password}는 항상
 * {@code ****}, {@code secret_ref}도 응답에 포함하지 않는다(database-registry.md §3).
 *
 * @param roles 파생 역할(파이프라인 사용 이력 기반). {@code source} / {@code sink}.
 */
public record DatabaseResponse(
        String id,
        String name,
        String engine,
        String host,
        int port,
        String dbName,
        String username,
        String password,
        String cdcReadinessStatus,
        String sinkReadinessStatus,
        String connectionStatus,
        String connectionError,
        Instant connectionCheckedAt,
        List<String> roles,
        Instant createdAt
) {

    private static final String MASKED = "****";

    public static DatabaseResponse of(DatasourceEntity e, List<String> roles) {
        return new DatabaseResponse(
                e.getId().toString(),
                e.getName(),
                e.getDbType().name().toLowerCase(),
                e.getHost(),
                e.getPort(),
                e.getDbName(),
                e.getUsername(),
                MASKED,
                e.getCdcReadinessStatus(),
                e.getSinkReadinessStatus(),
                e.getConnectionStatus(),
                e.getConnectionError(),
                e.getConnectionCheckedAt(),
                roles,
                e.getCreatedAt()
        );
    }
}
