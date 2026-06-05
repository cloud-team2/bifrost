package com.bifrost.ops.provisioning.impl.strimzi;

import com.bifrost.ops.global.common.datasource.DbType;
import com.bifrost.ops.provisioning.dto.PipelineProvisionCommand;
import com.bifrost.ops.provisioning.naming.ConnectorNaming;
import com.bifrost.ops.secret.DbCredential;
import io.strimzi.api.kafka.model.connector.KafkaConnector;
import io.strimzi.api.kafka.model.connector.KafkaConnectorBuilder;
import org.springframework.stereotype.Component;

/**
 * Sink(JDBC) KafkaConnector CR mapper (설계 §2 4.2). CDC(direct)에서만 생성한다.
 *
 * <p>Debezium이 만든 source 토픽을 구독해 sink DB에 upsert로 적재한다.
 * <ul>
 *   <li>class: {@code io.confluent.connect.jdbc.JdbcSinkConnector}</li>
 *   <li>{@code tasksMax=3} (파티션 병렬, 설계 §4.2)</li>
 *   <li>{@code topics} = source 토픽({@link ConnectorNaming#topicName})</li>
 *   <li>{@code insert.mode=upsert}, {@code pk.mode=record_key} (중복 없는 적재)</li>
 * </ul>
 *
 * <p>sink DB 자격증명은 이 mapper에서만 {@code connection.password}에 주입하며 로그·State에
 * 남기지 않는다(설계 §1 보안).
 */
@Component
public class JdbcSinkConnectorMapper {

    public static final String JDBC_SINK_CLASS = "io.confluent.connect.jdbc.JdbcSinkConnector";

    /**
     * sink KafkaConnector CR을 만든다.
     *
     * @param command        파이프라인 생성 요청(DIRECT, source/sink 필수)
     * @param sinkCred       sink DB 자격증명(SecretStore.resolve 결과)
     * @param namespace      CR 네임스페이스
     * @param connectCluster strimzi.io/cluster 라벨 값
     */
    public KafkaConnector map(PipelineProvisionCommand command,
                              DbCredential sinkCred,
                              String namespace,
                              String connectCluster) {
        PipelineProvisionCommand.Endpoint src = command.source();
        PipelineProvisionCommand.Endpoint sink = command.sink();
        if (sink == null) {
            throw new IllegalArgumentException("sink endpoint required for JDBC sink mapping");
        }
        String name = ConnectorNaming.sinkConnectorName(command.pipelineId());
        // source가 적재하는 토픽을 그대로 구독
        String sourceTopic = ConnectorNaming.topicName(
                command.projectKey(), src.dbName(), src.schema(), src.table());

        return new KafkaConnectorBuilder()
                .withNewMetadata()
                    .withName(name)
                    .withNamespace(namespace)
                    .addToLabels(SourceDebeziumConnectorMapper.CLUSTER_LABEL, connectCluster)
                .endMetadata()
                .withNewSpec()
                    .withClassName(JDBC_SINK_CLASS)
                    .withTasksMax(3)
                    .addToConfig("topics", sourceTopic)
                    // 스키마 인지 JSON: source가 schemas.enable=true로 적재한 키(PK Struct)·값을 그대로 읽어야
                    // pk.mode=record_key와 auto.create가 동작한다(worker 기본값 false를 per-connector로 오버라이드).
                    .addToConfig("key.converter", "org.apache.kafka.connect.json.JsonConverter")
                    .addToConfig("key.converter.schemas.enable", "true")
                    .addToConfig("value.converter", "org.apache.kafka.connect.json.JsonConverter")
                    .addToConfig("value.converter.schemas.enable", "true")
                    .addToConfig("connection.url", jdbcUrl(sink))
                    .addToConfig("connection.user", sinkCred.user())
                    .addToConfig("connection.password", sinkCred.password())
                    // 중복 없는 적재 (설계 §4.2)
                    .addToConfig("insert.mode", "upsert")
                    .addToConfig("pk.mode", "record_key")
                    .addToConfig("auto.create", "true")
                    .addToConfig("auto.evolve", "true")
                    // Debezium envelope 평탄화(unwrap) → 토픽명을 테이블명으로 축약(route)
                    .addToConfig("transforms", "unwrap,route")
                    .addToConfig("transforms.unwrap.type",
                            "io.debezium.transforms.ExtractNewRecordState")
                    // DELETE 이벤트는 sink에 전달하지 않음 (Debezium 3.x).
                    // delete.tombstone.handling.mode=drop: delete 레코드와 tombstone을 모두 제거한다.
                    // (구 delete.handling.mode/drop.tombstones는 Debezium 2.5+에서 이 옵션으로 통합·제거됨.
                    //  none이면 값이 null인 delete 레코드가 흘러가 delete.enabled=false인 JDBC sink를 죽인다.)
                    .addToConfig("transforms.unwrap.delete.tombstone.handling.mode", "drop")
                    // 토픽명 `cdc.table.{project}.{db}.{schema}.{table}`을 마지막 세그먼트(테이블명)로
                    // 축약한다. JDBC sink는 기본적으로 토픽명을 테이블 식별자로 쓰는데, 점(.)이 들어가면
                    // MariaDB/MySQL이 `cdc`.`table`처럼 catalog.table로 오해해 적재가 깨진다.
                    .addToConfig("transforms.route.type",
                            "org.apache.kafka.connect.transforms.RegexRouter")
                    .addToConfig("transforms.route.regex", ".*\\.([^.]+)$")
                    .addToConfig("transforms.route.replacement", "$1")
                .endSpec()
                .build();
    }

    private String jdbcUrl(PipelineProvisionCommand.Endpoint sink) {
        String scheme = switch (sink.engine()) {
            case POSTGRESQL -> "postgresql";
            case MARIADB -> "mariadb";
        };
        return "jdbc:" + scheme + "://" + sink.host() + ":" + sink.port() + "/" + sink.dbName();
    }
}
