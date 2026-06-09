package com.bifrost.ops.provisioning.impl.strimzi;

import com.bifrost.ops.global.common.datasource.DbType;
import com.bifrost.ops.provisioning.dto.PipelineProvisionCommand;
import com.bifrost.ops.provisioning.naming.ConnectorNaming;
import com.bifrost.ops.secret.DbCredential;
import io.strimzi.api.kafka.model.connector.KafkaConnector;
import io.strimzi.api.kafka.model.connector.KafkaConnectorBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

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

    /** MariaDB schema history를 저장할 Kafka bootstrap. Connect 내부 접근이므로 plain 9092 사용. */
    private final String kafkaBootstrapServers;

    public SourceDebeziumConnectorMapper(
            @Value("${spring.kafka.bootstrap-servers:platform-kafka-kafka-bootstrap.platform-kafka.svc.cluster.local:9092}")
            String kafkaBootstrapServers) {
        this.kafkaBootstrapServers = kafkaBootstrapServers;
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
        String topicPrefix = ConnectorNaming.topicPrefix(command.projectKey(), src.dbName(), src.datasourceId());
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
                    // 토픽 네이밍 (Debezium이 .{schema}.{table} 자동 부여)
                    .addToConfig("topic.prefix", topicPrefix)
                    .addToConfig("table.include.list", tableInclude)
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
                    .addToConfig("topic.creation.default.partitions", "6")
                    .addToConfig("topic.creation.default.replication.factor", "3")
                .endSpec();

        applyEngineSpecifics(builder, src, command.projectKey(), pipelineId);
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
