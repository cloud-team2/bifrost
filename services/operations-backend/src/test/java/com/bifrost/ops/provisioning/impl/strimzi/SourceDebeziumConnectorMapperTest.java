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

/** Source Debezium mapper(설계 §2 4.1) 단위 테스트. */
class SourceDebeziumConnectorMapperTest {

    private final SourceDebeziumConnectorMapper mapper =
            new SourceDebeziumConnectorMapper("localhost:9092", 6, 3);
    private static final String NS = "platform-kafka";
    private static final String CLUSTER = "platform-connect";

    private PipelineProvisionCommand command(DbType engine) {
        return new PipelineProvisionCommand(
                UUID.fromString("11111111-2222-3333-4444-555555555555"),
                "team2",
                PipelinePattern.FAN_OUT,
                new PipelineProvisionCommand.Endpoint(
                        engine, "db.internal", 5432, "shop",
                        UUID.fromString("12345678-aaaa-bbbb-cccc-000000000000"), "public", "orders", "ref-source"),
                null);
    }

    @Test
    void mapsPostgresSourceWithNamingAndCredentials() {
        KafkaConnector cr = mapper.map(
                command(DbType.POSTGRESQL), new DbCredential("svc", "pw"), NS, CLUSTER);

        assertThat(cr.getMetadata().getName())
                .isEqualTo("11111111-2222-3333-4444-555555555555-source");
        assertThat(cr.getMetadata().getNamespace()).isEqualTo(NS);
        assertThat(cr.getMetadata().getLabels())
                .containsEntry(SourceDebeziumConnectorMapper.CLUSTER_LABEL, CLUSTER);
        assertThat(cr.getSpec().getClassName())
                .isEqualTo(SourceDebeziumConnectorMapper.POSTGRES_CLASS);
        assertThat(cr.getSpec().getTasksMax()).isEqualTo(1);

        Map<String, Object> config = cr.getSpec().getConfig();
        // (#365) topic.prefix = 최종 토픽명(테이블 단위 유일한 Debezium server). Debezium이 또 붙이는
        // .{schema}.{table} 중복분은 route SMT로 제거 → 최종 토픽명은 동일하게 유지된다.
        assertThat(config).containsEntry("topic.prefix", "cdc.table.team2.shop-12345678.public.orders");
        assertThat(config).containsEntry("table.include.list", "public.orders");
        // 토픽 자동생성 기본값(운영 기준 6/3) — 로컬은 env로 1 주입(#402)
        assertThat(config).containsEntry("topic.creation.default.partitions", "6");
        assertThat(config).containsEntry("topic.creation.default.replication.factor", "3");
        assertThat(config).containsEntry("transforms", "route");
        assertThat(config).containsEntry("transforms.route.type",
                "org.apache.kafka.connect.transforms.RegexRouter");
        assertThat(config).containsEntry("transforms.route.regex", "(.*)\\.public\\.orders$");
        assertThat(config).containsEntry("transforms.route.replacement", "$1");
        assertThat(config).containsEntry("database.hostname", "db.internal");
        assertThat(config).containsEntry("database.user", "svc");
        assertThat(config).containsEntry("database.password", "pw");
        assertThat(config).containsEntry("plugin.name", "pgoutput");
        assertThat(config).containsKey("slot.name");
        assertThat(config).containsKey("publication.name");
        // (#365) publication을 이 테이블로만 한정 → slot이 다른 테이블 변경을 stream하지 않아 메트릭 분리
        assertThat(config).containsEntry("publication.autocreate.mode", "filtered");
    }

    @Test
    void mapsMariadbSourceWithServerId() {
        KafkaConnector cr = mapper.map(
                command(DbType.MARIADB), new DbCredential("svc", "pw"), NS, CLUSTER);

        assertThat(cr.getSpec().getClassName())
                .isEqualTo(SourceDebeziumConnectorMapper.MARIADB_CLASS);
        Map<String, Object> config = cr.getSpec().getConfig();
        assertThat(config).containsKey("database.server.id");
        // database.include.list = dbName (단일 DB 범위로 좁힘, schema 아님)
        assertThat(config).containsEntry("database.include.list", "shop");
        assertThat(config).containsKey("schema.history.internal.kafka.bootstrap.servers");
        assertThat(config).containsKey("schema.history.internal.kafka.topic");
        assertThat(config).containsEntry("snapshot.mode", "initial");
        assertThat(config).doesNotContainKey("plugin.name");
    }
}
