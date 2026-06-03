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
                    .addToConfig("connection.url", jdbcUrl(sink))
                    .addToConfig("connection.user", sinkCred.user())
                    .addToConfig("connection.password", sinkCred.password())
                    // 중복 없는 적재 (설계 §4.2)
                    .addToConfig("insert.mode", "upsert")
                    .addToConfig("pk.mode", "record_key")
                    .addToConfig("auto.create", "true")
                    .addToConfig("auto.evolve", "true")
                    // Debezium envelope을 평탄화해 sink 테이블 컬럼과 매핑
                    .addToConfig("transforms", "unwrap")
                    .addToConfig("transforms.unwrap.type",
                            "io.debezium.transforms.ExtractNewRecordState")
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
