package com.bifrost.ops.provisioning.impl.strimzi;

import com.bifrost.ops.global.common.datasource.DbType;
import com.bifrost.ops.provisioning.dto.PipelinePattern;
import com.bifrost.ops.provisioning.dto.PipelineProvisionCommand;
import com.bifrost.ops.secret.DbCredential;
import io.strimzi.api.kafka.model.connector.KafkaConnector;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** CDC Sink JDBC mapper(설계 §2 4.2) 단위 테스트. */
class JdbcSinkConnectorMapperTest {

    private final JdbcSinkConnectorMapper mapper = new JdbcSinkConnectorMapper();
    private static final String NS = "platform-kafka";
    private static final String CLUSTER = "platform-connect";

    private PipelineProvisionCommand directCommand() {
        return new PipelineProvisionCommand(
                UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"),
                "team2",
                PipelinePattern.DIRECT,
                new PipelineProvisionCommand.Endpoint(
                        DbType.POSTGRESQL, "src.internal", 5432, "shop", "public", "orders", "ref-src"),
                new PipelineProvisionCommand.Endpoint(
                        DbType.MARIADB, "sink.internal", 3306, "warehouse", null, null, "ref-sink"));
    }

    @Test
    void mapsJdbcSinkConsumingSourceTopic() {
        KafkaConnector cr = mapper.map(
                directCommand(), new DbCredential("sinker", "pw"), NS, CLUSTER);

        assertThat(cr.getMetadata().getName())
                .isEqualTo("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee-sink");
        assertThat(cr.getSpec().getClassName()).isEqualTo(JdbcSinkConnectorMapper.JDBC_SINK_CLASS);
        assertThat(cr.getSpec().getTasksMax()).isEqualTo(3);

        Map<String, Object> config = cr.getSpec().getConfig();
        // source가 적재하는 토픽을 구독
        assertThat(config).containsEntry("topics", "cdc.table.team2.shop.public.orders");
        assertThat(config).containsEntry("connection.url", "jdbc:mariadb://sink.internal:3306/warehouse");
        assertThat(config).containsEntry("connection.user", "sinker");
        assertThat(config).containsEntry("insert.mode", "upsert");
        assertThat(config).containsEntry("pk.mode", "record_key");
        // Debezium envelope 평탄화 SMT
        assertThat(config).containsEntry("transforms", "unwrap");
        assertThat(config).containsEntry("transforms.unwrap.type",
                "io.debezium.transforms.ExtractNewRecordState");
        assertThat(config).containsEntry("transforms.unwrap.delete.handling.mode", "none");
        assertThat(config).containsEntry("transforms.unwrap.drop.tombstones", "true");
    }
}
