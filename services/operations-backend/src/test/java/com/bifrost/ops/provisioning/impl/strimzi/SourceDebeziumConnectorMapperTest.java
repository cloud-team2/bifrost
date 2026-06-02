package com.bifrost.ops.provisioning.impl.strimzi;

import com.bifrost.ops.common.datasource.DbType;
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

    private final SourceDebeziumConnectorMapper mapper = new SourceDebeziumConnectorMapper();
    private static final String NS = "platform-kafka";
    private static final String CLUSTER = "platform-connect";

    private PipelineProvisionCommand command(DbType engine) {
        return new PipelineProvisionCommand(
                UUID.fromString("11111111-2222-3333-4444-555555555555"),
                "team2",
                PipelinePattern.FAN_OUT,
                new PipelineProvisionCommand.Endpoint(
                        engine, "db.internal", 5432, "shop", "public", "orders", "ref-source"),
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
        assertThat(config).containsEntry("topic.prefix", "cdc.table.team2.shop");
        assertThat(config).containsEntry("table.include.list", "public.orders");
        assertThat(config).containsEntry("database.hostname", "db.internal");
        assertThat(config).containsEntry("database.user", "svc");
        assertThat(config).containsEntry("database.password", "pw");
        assertThat(config).containsEntry("plugin.name", "pgoutput");
        assertThat(config).containsKey("slot.name");
        assertThat(config).containsKey("publication.name");
    }

    @Test
    void mapsMariadbSourceWithServerId() {
        KafkaConnector cr = mapper.map(
                command(DbType.MARIADB), new DbCredential("svc", "pw"), NS, CLUSTER);

        assertThat(cr.getSpec().getClassName())
                .isEqualTo(SourceDebeziumConnectorMapper.MARIADB_CLASS);
        Map<String, Object> config = cr.getSpec().getConfig();
        assertThat(config).containsKey("database.server.id");
        assertThat(config).containsEntry("database.include.list", "public");
        assertThat(config).doesNotContainKey("plugin.name");
    }
}
