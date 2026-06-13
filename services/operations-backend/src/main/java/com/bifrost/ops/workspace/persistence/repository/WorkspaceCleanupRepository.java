package com.bifrost.ops.workspace.persistence.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * 워크스페이스 삭제 전용 메타데이터 정리 경계.
 *
 * <p>여러 테이블의 FK 방향이 tenant를 루트로 삼으므로 삭제 순서를 한 곳에 고정한다.
 */
@Repository
public class WorkspaceCleanupRepository {

    private final JdbcTemplate jdbc;

    public WorkspaceCleanupRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void deleteWorkspaceMetadata(UUID workspaceId) {
        jdbc.update("DELETE FROM audit_events WHERE tenant_id = ?", workspaceId);
        jdbc.update("DELETE FROM events WHERE tenant_id = ?", workspaceId);
        jdbc.update("DELETE FROM incidents WHERE tenant_id = ?", workspaceId);
        jdbc.update("DELETE FROM evidence_ref WHERE tenant_id = ?", workspaceId);
        jdbc.update("DELETE FROM approval WHERE tenant_id = ?", workspaceId);
        jdbc.update("DELETE FROM change_ticket WHERE tenant_id = ?", workspaceId);
        jdbc.update("DELETE FROM idempotency_key WHERE tenant_id = ?", workspaceId);
        jdbc.update("DELETE FROM discovered_services WHERE tenant_id = ?", workspaceId);
        jdbc.update("DELETE FROM kafka_principal WHERE workspace_id = ?", workspaceId);
        jdbc.update("DELETE FROM workspace_settings WHERE workspace_id = ?", workspaceId);
        jdbc.update("DELETE FROM project_member WHERE workspace_id = ?", workspaceId);
        jdbc.update("DELETE FROM tenants WHERE id = ?", workspaceId);
    }
}
