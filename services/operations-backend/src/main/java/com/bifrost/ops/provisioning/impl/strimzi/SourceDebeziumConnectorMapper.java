package com.bifrost.ops.provisioning.impl.strimzi;

import com.bifrost.ops.global.common.datasource.DbType;
import com.bifrost.ops.provisioning.dto.PipelineProvisionCommand;
import com.bifrost.ops.provisioning.naming.ConnectorNaming;
import com.bifrost.ops.secret.DbCredential;
import io.strimzi.api.kafka.model.connector.KafkaConnector;
import io.strimzi.api.kafka.model.connector.KafkaConnectorBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Source(Debezium) KafkaConnector CR mapper (설계 §2 4.1).
 *
 * <p>{@link PipelineProvisionCommand}의 source 엔드포인트와 {@link SecretStore}에서 해석한
 * {@link DbCredential}을 Strimzi {@code KafkaConnector} CR로 변환한다. 파이프라인 1개 =
 * 단일 테이블이므로 {@code table.include.list}는 항상 단일 테이블이고 {@code tasksMax=1}
 * (WAL/Binlog 순서 보장).
 *
 * <p>엔진별 connector class:
 * <ul>
 *   <li>PostgreSQL → {@code io.debezium.connector.postgresql.PostgresConnector} (plugin.name=pgoutput)</li>
 *   <li>MariaDB    → {@code io.debezium.connector.mariadb.MariaDbConnector}</li>
 * </ul>
 *
 * <p>자격증명({@code database.password})은 이 mapper에서만 config에 주입하고
 * 로그·State·응답에는 남기지 않는다(설계 §2 4.1, §1 보안).
 */
@Component
public class SourceDebeziumConnectorMapper {

    public static final String CLUSTER_LABEL = "strimzi.io/cluster";
    public static final String POSTGRES_CLASS = "io.debezium.connector.postgresql.PostgresConnector";
    public static final String MARIADB_CLASS = "io.debezium.connector.mariadb.MariaDbConnector";
    /** 데이터플레인 추적 SMT(#371/#438). 변경 이벤트마다 span 생성 + trace context를 Kafka 헤더에 주입. */
    public static final String TRACING_SMT_TYPE = "io.debezium.transforms.tracing.ActivateTracingSpan";
    /**
     * Postgres timestamptz 커스텀 컨버터(#425). Connect 이미지의 plugin 디렉토리에 동봉되며
     * (connect-plugins/timestamptz-converter), timestamptz를 Connect Timestamp 논리 타입으로 바꿔
     * JDBC sink의 varchar 적재 → 타입 불일치를 막는다.
     */
    public static final String TIMESTAMPTZ_CONVERTER_TYPE = "com.bifrost.connect.converter.TimestamptzConverter";

    /**
     * source 커넥터 config의 transforms에 데이터플레인 tracing SMT를 on/off (per-pipeline 토글, #438).
     * 기존 transforms(예: route)는 보존하고 {@code tracing}만 추가/제거한다.
     */
    public static void setTracingSmt(Map<String, Object> config, boolean enabled) {
        String existing = config.get("transforms") == null ? "" : String.valueOf(config.get("transforms"));
        List<String> parts = new ArrayList<>();
        for (String p : existing.split(",")) {
            p = p.trim();
            if (!p.isEmpty() && !p.equals("tracing")) parts.add(p);
        }
        if (enabled) {
            parts.add("tracing");
            config.put("transforms", String.join(",", parts));
            config.put("transforms.tracing.type", TRACING_SMT_TYPE);
        } else {
            config.put("transforms", String.join(",", parts));
            config.remove("transforms.tracing.type");
        }
    }

    /** MariaDB schema history를 저장할 Kafka bootstrap. Connect 내부 접근이므로 plain 9092 사용. */
    private final String kafkaBootstrapServers;
    /** 자동 생성 토픽의 partition/replication factor. 운영 기본 6/3, 로컬(단일 브로커)은 env로 1 주입. */
    private final int topicPartitions;
    private final int topicReplicationFactor;

    /** 데이터플레인 추적(#371): on이면 Debezium tracing SMT를 커넥터에 추가. 기본 off(오버헤드 없음). */
    private final boolean dataplaneTracingEnabled;

    public SourceDebeziumConnectorMapper(
            @Value("${spring.kafka.bootstrap-servers:platform-kafka-kafka-bootstrap.platform-kafka.svc.cluster.local:9092}")
            String kafkaBootstrapServers,
            @Value("${tracing.dataplane.enabled:false}") boolean dataplaneTracingEnabled,
            @Value("${pipeline.topic.partitions:6}") int topicPartitions,
            @Value("${pipeline.topic.replication-factor:3}") int topicReplicationFactor) {
        this.kafkaBootstrapServers = kafkaBootstrapServers;
        this.dataplaneTracingEnabled = dataplaneTracingEnabled;
        this.topicPartitions = topicPartitions;
        this.topicReplicationFactor = topicReplicationFactor;
    }

    /**
     * source KafkaConnector CR을 만든다.
     *
     * @param command        파이프라인 생성 요청(source 필수)
     * @param cred           SecretStore.resolve 결과(생성 시점에만 사용)
     * @param namespace      CR 네임스페이스(예: platform-kafka)
     * @param connectCluster strimzi.io/cluster 라벨 값(예: platform-connect)
     */
    public KafkaConnector map(PipelineProvisionCommand command,
                              DbCredential cred,
                              String namespace,
                              String connectCluster) {
        PipelineProvisionCommand.Endpoint src = command.source();
        UUID pipelineId = command.pipelineId();
        String name = ConnectorNaming.sourceConnectorName(pipelineId);
        // (#365) Debezium 논리 서버명(=topic.prefix=메트릭 server 라벨)을 테이블 단위로 유일화한다.
        // 같은 소스 DB의 여러 파이프라인이 server를 공유해 이벤트/소스지연 메트릭이 섞이던 문제를 막는다.
        // 최종 토픽명을 prefix로 쓰고, Debezium이 또 붙이는 .{schema}.{table} 중복분은 아래 route SMT로 제거.
        String serverName = ConnectorNaming.topicName(
                command.pattern(), command.projectKey(), src.dbName(), src.datasourceId(), src.schema(), src.table());
        String tableInclude = src.schema() + "." + src.table();
        String connectorClass = connectorClass(src.engine());

        KafkaConnectorBuilder builder = new KafkaConnectorBuilder()
                .withNewMetadata()
                    .withName(name)
                    .withNamespace(namespace)
                    .addToLabels(CLUSTER_LABEL, connectCluster)
                .endMetadata()
                .withNewSpec()
                    .withClassName(connectorClass)
                    .withTasksMax(1)
                    // 공통 DB 접속 정보
                    .addToConfig("database.hostname", src.host())
                    .addToConfig("database.port", String.valueOf(src.port()))
                    .addToConfig("database.user", cred.user())
                    .addToConfig("database.password", cred.password())
                    .addToConfig("database.dbname", src.dbName())
                    // 토픽 네이밍: topic.prefix를 최종 토픽명으로(테이블 단위 유일한 server). Debezium이 그 뒤에
                    // 또 .{schema}.{table}을 붙이므로, 아래 route SMT로 중복분을 떼어 최종 토픽명을 복원한다.
                    .addToConfig("topic.prefix", serverName)
                    .addToConfig("table.include.list", tableInclude)
                    // Debezium 자동 부여 .{schema}.{table} 중복 제거 → 최종 토픽 = topic.prefix(원래 토픽명, #365).
                    // 단일 테이블 커넥터라 데이터 토픽만 매칭(끝이 .{schema}.{table}); heartbeat는 기본 비활성.
                    .addToConfig("transforms", "route")
                    .addToConfig("transforms.route.type", "org.apache.kafka.connect.transforms.RegexRouter")
                    .addToConfig("transforms.route.regex", "(.*)\\." + src.schema() + "\\." + src.table() + "$")
                    .addToConfig("transforms.route.replacement", "$1")
                    // 스키마 인지 JSON: JDBC sink가 키(PK Struct)·값 타입을 알 수 있도록 per-connector로
                    // schemas.enable=true 강제(worker 기본값 false를 오버라이드). sink의 pk.mode=record_key가
                    // 스키마 없는 HashMap 키를 거부하는 문제를 막는다.
                    .addToConfig("key.converter", "org.apache.kafka.connect.json.JsonConverter")
                    .addToConfig("key.converter.schemas.enable", "true")
                    .addToConfig("value.converter", "org.apache.kafka.connect.json.JsonConverter")
                    .addToConfig("value.converter.schemas.enable", "true")
                    // 시간 타입을 Kafka Connect 논리 타입(Date/Time/Timestamp)으로 매핑한다.
                    // 기본(adaptive)은 created_at을 epoch 마이크로초(int64)로 보내 JDBC sink가 대상 컬럼을
                    // BIGINT로 만들어버린다 → connect 모드면 SQL TIMESTAMP로 자연스럽게 적재된다.
                    .addToConfig("time.precision.mode", "connect")
                    // 자동 토픽 생성 기본값 (설계 §2 4.1)
                    .addToConfig("topic.creation.default.partitions", String.valueOf(topicPartitions))
                    .addToConfig("topic.creation.default.replication.factor", String.valueOf(topicReplicationFactor))
                .endSpec();

        applyEngineSpecifics(builder, src, command.projectKey(), pipelineId);
        if (dataplaneTracingEnabled) {
            // #371 데이터플레인 추적: Debezium ActivateTracingSpan SMT를 route 뒤에 체이닝한다.
            // 변경 이벤트마다 span 생성 + trace context를 Kafka 헤더에 주입 → sink까지 한 trace로 연결.
            // (전제: Connect 워커에 OTel agent. 워커 미계측 시 SMT는 사실상 no-op.)
            builder.editSpec()
                    .addToConfig("transforms", "route,tracing")
                    .addToConfig("transforms.tracing.type", TRACING_SMT_TYPE)
                    .endSpec();
        }
        return builder.build();
    }

    private void applyEngineSpecifics(KafkaConnectorBuilder builder,
                                      PipelineProvisionCommand.Endpoint src,
                                      String projectKey,
                                      UUID pipelineId) {
        switch (src.engine()) {
            case POSTGRESQL -> builder.editSpec()
                    .addToConfig("plugin.name", "pgoutput")
                    // 프로젝트/파이프라인별 slot·publication 격리 (영소문자·숫자·언더스코어)
                    .addToConfig("slot.name", slotName(projectKey, pipelineId))
                    .addToConfig("publication.name", publicationName(projectKey, pipelineId))
                    // (#365) publication을 이 파이프라인의 테이블로만 한정(filtered). 기본값(all_tables)이면
                    // slot이 DB의 모든 테이블 변경을 stream해 "events seen" 메트릭이 테이블 무관하게 합산된다.
                    .addToConfig("publication.autocreate.mode", "filtered")
                    // (#425) timestamptz는 time.precision.mode와 무관하게 Debezium이 ZonedTimestamp(문자열)로
                    // 방출 → JDBC sink가 varchar 적재 → 타입 불일치. 커스텀 컨버터로 Connect Timestamp로 변환한다.
                    // (컨버터 JAR은 Connect 이미지에 동봉, connect-plugins/timestamptz-converter)
                    // Debezium 규약: 타입 키는 `<alias>.type` (converters.<alias>.type 아님, #462).
                    .addToConfig("converters", "timestamptz")
                    .addToConfig("timestamptz.type", TIMESTAMPTZ_CONVERTER_TYPE)
                    .endSpec();
            // MariaDB(Debezium binlog): server id는 클러스터 내 유일해야 하므로 pipelineId 해시 사용.
            // 단일 테이블만 캡처하므로 database.include.list = dbName으로 좁힌다.
            // schema.history는 Connect 내부 Kafka 토픽에 저장하며 pipeline별로 격리한다.
            case MARIADB -> builder.editSpec()
                    .addToConfig("database.server.id", String.valueOf(serverId(pipelineId)))
                    .addToConfig("database.include.list", src.dbName())
                    // schema history: pipeline별 토픽으로 격리, Connect 내부 Kafka 접속
                    .addToConfig("schema.history.internal.kafka.bootstrap.servers", kafkaBootstrapServers)
                    .addToConfig("schema.history.internal.kafka.topic",
                            schemaHistoryTopic(projectKey, pipelineId))
                    // snapshot.mode: initial(첫 기동 시 전체 스냅샷 후 CDC). 이미 기동된 적 있으면 schema_only.
                    .addToConfig("snapshot.mode", "initial")
                    .endSpec();
            default -> throw new IllegalArgumentException("unsupported engine: " + src.engine());
        }
    }

    private String connectorClass(DbType engine) {
        return switch (engine) {
            case POSTGRESQL -> POSTGRES_CLASS;
            case MARIADB -> MARIADB_CLASS;
        };
    }

    /** Postgres replication slot 이름(영소문자·숫자·언더스코어, ≤63). */
    private String slotName(String projectKey, UUID pipelineId) {
        String pid = pipelineId.toString().replace("-", "").substring(0, 8);
        return ("bif_" + projectKey + "_" + pid).toLowerCase().replaceAll("[^a-z0-9_]", "_");
    }

    private String publicationName(String projectKey, UUID pipelineId) {
        return slotName(projectKey, pipelineId) + "_pub";
    }

    /** binlog server id: pipelineId 해시 기반 양수(1..2^31-1). */
    private int serverId(UUID pipelineId) {
        int h = pipelineId.hashCode() & 0x7fffffff;
        return h == 0 ? 1 : h;
    }

    /** MariaDB schema history 토픽 이름: pipeline별 격리. */
    private String schemaHistoryTopic(String projectKey, UUID pipelineId) {
        String pid = pipelineId.toString().replace("-", "").substring(0, 8);
        return "schema-history." + projectKey + "." + pid;
    }
}
