package com.bifrost.ops.provisioning.naming;

import com.bifrost.ops.provisioning.dto.PipelinePattern;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * connector naming 규칙(설계 §2) 단위 테스트. 토픽 prefix/이름, ACL prefix, KafkaUser,
 * Source/Sink CR 이름이 규칙대로 만들어지는지 고정한다.
 */
class ConnectorNamingTest {

    private static final String PROJECT_KEY = "team2";
    private static final String DB_NAME = "shop";
    // 토픽 슬러그는 datasource id 앞 8 hex를 dbName 뒤에 붙인다(#265)
    private static final UUID DS_ID = UUID.fromString("a1b2c3d4-0000-0000-0000-000000000000");

    @Test
    void buildsTopicPrefixCdcForDirect() {
        assertThat(ConnectorNaming.topicPrefix(PipelinePattern.DIRECT, PROJECT_KEY, DB_NAME, DS_ID))
                .isEqualTo("cdc.table.team2.shop-a1b2c3d4");
    }

    @Test
    void buildsTopicPrefixEdaForFanOut() {
        // EDA(FAN_OUT)는 eda.table.* root (#447)
        assertThat(ConnectorNaming.topicPrefix(PipelinePattern.FAN_OUT, PROJECT_KEY, DB_NAME, DS_ID))
                .isEqualTo("eda.table.team2.shop-a1b2c3d4");
    }

    @Test
    void buildsTableCentricTopicName() {
        assertThat(ConnectorNaming.topicName(PipelinePattern.DIRECT, PROJECT_KEY, DB_NAME, DS_ID, "public", "orders"))
                .isEqualTo("cdc.table.team2.shop-a1b2c3d4.public.orders");
        assertThat(ConnectorNaming.topicName(PipelinePattern.FAN_OUT, PROJECT_KEY, DB_NAME, DS_ID, "public", "orders"))
                .isEqualTo("eda.table.team2.shop-a1b2c3d4.public.orders");
    }

    @Test
    void differentDatasourceIdsAvoidCollisionForSameDbName() {
        UUID other = UUID.fromString("ffffffff-0000-0000-0000-000000000000");
        assertThat(ConnectorNaming.topicPrefix(PipelinePattern.DIRECT, PROJECT_KEY, DB_NAME, DS_ID))
                .isNotEqualTo(ConnectorNaming.topicPrefix(PipelinePattern.DIRECT, PROJECT_KEY, DB_NAME, other));
    }

    @Test
    void buildsAclPrefixWithTrailingDot() {
        assertThat(ConnectorNaming.topicAclPrefix(PROJECT_KEY))
                .isEqualTo("cdc.table.team2.");
    }

    @Test
    void buildsKafkaUserName() {
        assertThat(ConnectorNaming.kafkaUserName(PROJECT_KEY))
                .isEqualTo("proj-team2-user");
    }

    @Test
    void buildsSourceAndSinkConnectorNames() {
        UUID pipelineId = UUID.fromString("11111111-2222-3333-4444-555555555555");
        assertThat(ConnectorNaming.sourceConnectorName(pipelineId))
                .isEqualTo("11111111-2222-3333-4444-555555555555-source");
        assertThat(ConnectorNaming.sinkConnectorName(pipelineId))
                .isEqualTo("11111111-2222-3333-4444-555555555555-sink");
    }

    @Test
    void rejectsBlankInputs() {
        assertThatThrownBy(() -> ConnectorNaming.topicPrefix(PipelinePattern.DIRECT, "", DB_NAME, DS_ID))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ConnectorNaming.topicPrefix(PipelinePattern.DIRECT, PROJECT_KEY, DB_NAME, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ConnectorNaming.sourceConnectorName(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
