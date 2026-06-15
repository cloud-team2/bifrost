package com.bifrost.ops.pipeline.service;

import com.bifrost.ops.auth.jwt.AuthenticatedUser;
import com.bifrost.ops.database.connection.DynamicDataSourceFactory;
import com.bifrost.ops.database.persistence.entity.DatasourceEntity;
import com.bifrost.ops.database.persistence.repository.DatasourceRepository;
import com.bifrost.ops.global.common.datasource.DbType;
import com.bifrost.ops.global.common.error.ApiException;
import com.bifrost.ops.global.common.error.ErrorCode;
import com.bifrost.ops.pipeline.dto.SyncStatusResponse;
import com.bifrost.ops.provisioning.dto.ConnectorKind;
import com.bifrost.ops.provisioning.dto.PipelinePattern;
import com.bifrost.ops.pipeline.persistence.entity.PipelineEntity;
import com.bifrost.ops.pipeline.persistence.repository.PipelineRepository;
import com.bifrost.ops.provisioning.persistence.entity.ConnectorEntity;
import com.bifrost.ops.provisioning.persistence.repository.ConnectorRepository;
import com.bifrost.ops.secret.SecretStore;
import com.bifrost.ops.workspace.WorkspaceAccessGuard;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * CDC(direct) 파이프라인의 동기화 상태 조회(#107, 상세 Sync 탭).
 *
 * <p>source/sink DB에 각각 {@code SELECT COUNT(*)}를 실행해 실제 행수를 비교한다. 자격증명은
 * {@link SecretStore}에서 resolve하고 읽기 전용 연결을 짧게 열고 닫는다({@link DynamicDataSourceFactory}).
 * 접속 실패·테이블 미존재(생성 중)는 -1로 반환해 화면이 "준비중"을 표시할 수 있게 한다.
 */
@Service
public class PipelineSyncService {

    private static final Logger log = LoggerFactory.getLogger(PipelineSyncService.class);

    private static final long ADMIN_TIMEOUT_SEC = 5L;
    private static final int COUNT_QUERY_TIMEOUT_SEC = 4;
    private static final long COUNT_CONNECTION_TIMEOUT_MS = 2000L;

    private final PipelineRepository pipelineRepository;
    private final DatasourceRepository datasourceRepository;
    private final SecretStore secretStore;
    private final DynamicDataSourceFactory dataSourceFactory;
    private final WorkspaceAccessGuard accessGuard;
    private final AdminClient adminClient;
    private final ConnectorRepository connectorRepository;

    public PipelineSyncService(PipelineRepository pipelineRepository,
                               DatasourceRepository datasourceRepository,
                               SecretStore secretStore,
                               DynamicDataSourceFactory dataSourceFactory,
                               WorkspaceAccessGuard accessGuard,
                               AdminClient adminClient,
                               ConnectorRepository connectorRepository) {
        this.pipelineRepository = pipelineRepository;
        this.datasourceRepository = datasourceRepository;
        this.secretStore = secretStore;
        this.dataSourceFactory = dataSourceFactory;
        this.accessGuard = accessGuard;
        this.adminClient = adminClient;
        this.connectorRepository = connectorRepository;
    }

    public SyncStatusResponse syncStatus(UUID wsId, AuthenticatedUser principal, UUID pipelineId) {
        accessGuard.requireAccess(wsId, principal);
        PipelineEntity p = pipelineRepository.findByIdAndTenantId(pipelineId, wsId)
                .orElseThrow(() -> new ApiException(ErrorCode.PIPELINE_NOT_FOUND, "파이프라인을 찾을 수 없습니다"));
        if (p.getPattern() == PipelinePattern.FAN_OUT) {
            return SyncStatusResponse.notApplicable();
        }

        DatasourceEntity source = requireDatasource(wsId, p.getSourceDatasourceId());
        DatasourceEntity sink = requireDatasource(wsId, p.getSinkDatasourceId());

        // source: 등록된 schema.table / sink: 동일 테이블명(RegexRouter로 토픽→테이블명 축약), 기본 schema
        long sourceRows = countRows(source, p.getSchemaName(), p.getTableName());
        long sinkRows = countRows(sink, null, p.getTableName());
        long delta = (sourceRows < 0 || sinkRows < 0) ? -1 : sourceRows - sinkRows;

        // #501: 완료 판정 기준 — sink 커넥터 health + consumer lag.
        boolean sinkFailed = sinkFailed(pipelineId);
        long[] lagEnd = sinkLagAndEnd(p);   // [lag, endOffset], lag=-1이면 sink 미소비(준비중)
        return SyncStatusResponse.of(sourceRows, sinkRows, delta, Instant.now(),
                lagEnd[0], lagEnd[1], sinkFailed);
    }

    /** sink 커넥터/task가 FAILED 또는 PARTIALLY_FAILED인지(메타DB watcher 갱신값 기준). */
    private boolean sinkFailed(UUID pipelineId) {
        return connectorRepository.findByPipelineId(pipelineId).stream()
                .filter(c -> c.getKind() == ConnectorKind.SINK)
                .map(ConnectorEntity::getState)
                .anyMatch(s -> "FAILED".equals(s) || "PARTIALLY_FAILED".equals(s));
    }

    /**
     * sink consumer group의 lag·토픽 end offset. {@code [lag, endOffset]}.
     * sink가 아직 소비 시작 전(컨슈머 그룹 미존재)이면 lag=-1(준비중), 토픽/Kafka 조회 불가면 [-1,-1].
     */
    private long[] sinkLagAndEnd(PipelineEntity p) {
        String topic = p.getTopicName();
        if (topic == null || topic.isBlank()) return new long[]{-1, -1};
        String group = sinkConsumerGroup(p.getId());
        try {
            var td = adminClient.describeTopics(List.of(topic)).allTopicNames()
                    .get(ADMIN_TIMEOUT_SEC, TimeUnit.SECONDS).get(topic);
            if (td == null) return new long[]{-1, -1};
            Map<TopicPartition, OffsetSpec> latest = new HashMap<>();
            for (var tpi : td.partitions()) latest.put(new TopicPartition(topic, tpi.partition()), OffsetSpec.latest());
            var ends = adminClient.listOffsets(latest).all().get(ADMIN_TIMEOUT_SEC, TimeUnit.SECONDS);
            long end = ends.values().stream().mapToLong(o -> o.offset()).sum();

            Map<TopicPartition, OffsetAndMetadata> committed = adminClient.listConsumerGroupOffsets(group)
                    .partitionsToOffsetAndMetadata().get(ADMIN_TIMEOUT_SEC, TimeUnit.SECONDS);
            if (committed == null || committed.isEmpty()) return new long[]{-1, end};  // sink 미소비(준비중)
            long consumed = 0;
            for (var tp : latest.keySet()) {
                OffsetAndMetadata om = committed.get(tp);
                if (om != null) consumed += om.offset();
            }
            return new long[]{Math.max(0, end - consumed), end};
        } catch (Exception e) {
            log.debug("sink lag 조회 실패: topic={}, group={}, cause={}", topic, group, e.toString());
            return new long[]{-1, -1};
        }
    }

    /** sink 커넥터의 Kafka Connect consumer group: {@code connect-<sink cr_name>}(기본 {@code <pid>-sink}). */
    private String sinkConsumerGroup(UUID pipelineId) {
        String sinkName = connectorRepository.findByPipelineId(pipelineId).stream()
                .filter(c -> c.getKind() == ConnectorKind.SINK)
                .map(ConnectorEntity::getCrName)
                .filter(n -> n != null && !n.isBlank())
                .findFirst()
                .orElse(pipelineId + "-sink");
        return "connect-" + sinkName;
    }

    private DatasourceEntity requireDatasource(UUID wsId, UUID dbId) {
        return datasourceRepository.findByIdAndTenantId(dbId, wsId)
                .orElseThrow(() -> new ApiException(ErrorCode.DATABASE_NOT_FOUND, "데이터베이스를 찾을 수 없습니다"));
    }

    /** {@code SELECT COUNT(*)} 실행. 접속 실패·테이블 미존재는 -1. */
    private long countRows(DatasourceEntity ds, String schema, String table) {
        String from = qualifiedTable(ds.getDbType(), schema, table);
        String password = secretStore.resolve(ds.getSecretRef()).password();
        try (HikariDataSource dataSource = dataSourceFactory.create(ds, password, true, COUNT_CONNECTION_TIMEOUT_MS);
             Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement()) {
            st.setQueryTimeout(COUNT_QUERY_TIMEOUT_SEC);
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + from)) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (Exception e) {
            log.debug("행수 조회 실패: db={}, table={}, cause={}", ds.getId(), from, e.toString());
            return -1L;
        }
    }

    /** 엔진별 식별자 인용. schema/table은 파이프라인 생성 시 검증된 값. */
    private String qualifiedTable(DbType engine, String schema, String table) {
        return switch (engine) {
            case POSTGRESQL -> (schema != null && !schema.isBlank())
                    ? "\"" + schema + "\".\"" + table + "\""
                    : "\"" + table + "\"";
            case MARIADB -> "`" + table + "`";
        };
    }
}
