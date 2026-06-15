package com.bifrost.ops.database.service;

import com.bifrost.ops.database.dto.DatabaseSchemaResponse;
import com.bifrost.ops.database.dto.DatabaseSchemaResponse.ColumnSchema;
import com.bifrost.ops.database.dto.DatabaseSchemaResponse.TableSchema;
import com.bifrost.ops.database.inspector.DatabaseInspector;
import com.bifrost.ops.database.inspector.DatabaseInspectorFactory;
import com.bifrost.ops.database.persistence.entity.DatasourceEntity;
import com.bifrost.ops.database.persistence.repository.DatasourceRepository;
import com.bifrost.ops.global.common.error.ApiException;
import com.bifrost.ops.global.common.error.ErrorCode;
import com.bifrost.ops.secret.DbCredential;
import com.bifrost.ops.secret.SecretStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * 스키마 조회 오케스트레이션(#28, FR-016). 등록 DB를 scope로 찾고 SecretStore에서 자격증명을
 * resolve한 뒤 {@link DatabaseInspector}로 메타데이터를 읽는다.
 */
@Service
public class DatabaseSchemaService {

    private final DatasourceRepository repo;
    private final SecretStore secretStore;
    private final DatabaseInspectorFactory inspectorFactory;

    public DatabaseSchemaService(DatasourceRepository repo, SecretStore secretStore,
                                 DatabaseInspectorFactory inspectorFactory) {
        this.repo = repo;
        this.secretStore = secretStore;
        this.inspectorFactory = inspectorFactory;
    }

    @Transactional(readOnly = true)
    public DatabaseSchemaResponse getSchema(UUID tenantId, UUID dbId) {
        DatasourceEntity e = repo.findByIdAndTenantId(dbId, tenantId)
                .orElseThrow(() -> new ApiException(ErrorCode.DATABASE_NOT_FOUND,
                        "데이터베이스를 찾을 수 없습니다"));
        DbCredential cred = secretStore.resolve(e.getSecretRef());
        try (DatabaseInspector inspector = inspectorFactory.create(e, cred.password())) {
            return toResponse(inspector.listTables());
        } catch (ApiException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ApiException(ErrorCode.DATABASE_CONNECTION_FAILED, "스키마 조회 실패");
        }
    }

    private static DatabaseSchemaResponse toResponse(List<DatabaseInspector.TableInfo> tables) {
        return new DatabaseSchemaResponse(
                tables.stream()
                        .map(t -> new TableSchema(t.schema(), t.name(),
                                t.approximateRowCount(), t.totalSizeBytes(),
                                t.columns().stream()
                                        .map(c -> new ColumnSchema(
                                                c.name(), c.dataType(), c.nullable(),
                                                c.isPrimaryKey(), c.indexed()))
                                        .toList()))
                        .toList());
    }
}
