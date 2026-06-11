package com.bifrost.ops.pipeline.service;

import com.bifrost.ops.auth.jwt.AuthenticatedUser;
import com.bifrost.ops.global.common.error.ApiException;
import com.bifrost.ops.monitoring.query.KafkaMetricsQuery;
import com.bifrost.ops.monitoring.query.TraceQuery;
import com.bifrost.ops.pipeline.PipelineLifecycle;
import com.bifrost.ops.pipeline.dto.PipelineStageStatusResponse;
import com.bifrost.ops.pipeline.kafka.OffsetSnapshotStore;
import com.bifrost.ops.pipeline.kafka.RateResult;
import com.bifrost.ops.pipeline.persistence.entity.PipelineEntity;
import com.bifrost.ops.pipeline.persistence.repository.PipelineRepository;
import com.bifrost.ops.provisioning.dto.ConnectorKind;
import com.bifrost.ops.provisioning.dto.PipelinePattern;
import com.bifrost.ops.provisioning.persistence.entity.ConnectorEntity;
import com.bifrost.ops.provisioning.persistence.repository.ConnectorRepository;
import com.bifrost.ops.workspace.WorkspaceAccessGuard;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.admin.ConsumerGroupListing;
import org.apache.kafka.clients.admin.DescribeConsumerGroupsResult;
import org.apache.kafka.clients.admin.ListConsumerGroupOffsetsResult;
import org.apache.kafka.clients.admin.ListConsumerGroupsResult;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.ConsumerGroupState;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PipelineTopicServiceTest {

    @Mock private PipelineRepository pipelineRepository;
    @Mock private ConnectorRepository connectorRepository;
    @Mock private WorkspaceAccessGuard accessGuard;
    @Mock private OffsetSnapshotStore snapshotStore;
    @Mock private KafkaMetricsQuery kafkaMetricsQuery;

    private PipelineTopicService service() {
        return service(mock(AdminClient.class));
    }

    private PipelineTopicService service(AdminClient adminClient) {
        return new PipelineTopicService(pipelineRepository, connectorRepository, accessGuard,
                adminClient, snapshotStore, kafkaMetricsQuery, mock(TraceQuery.class));
    }

    @Test
    void stageStatusAttributesSinkFailureAsBottleneckWithSinkLag() {
        UUID wsId = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        AuthenticatedUser principal = new AuthenticatedUser(UUID.randomUUID(), wsId, "u@bifrost.io");

        PipelineEntity p = new PipelineEntity();
        p.setId(id);
        p.setTenantId(wsId);
        p.setPattern(PipelinePattern.DIRECT);   // CDC → source + sink 단계
        p.setStatus(PipelineLifecycle.ERROR);
        p.setTopicName("pipe.orders");
        when(pipelineRepository.findByIdAndTenantId(id, wsId)).thenReturn(Optional.of(p));

        when(connectorRepository.findByPipelineId(id)).thenReturn(List.of(
                connector(ConnectorKind.SOURCE, "RUNNING", null),
                connector(ConnectorKind.SINK, "FAILED", "PSQLException: relation does not exist")));

        // Prometheus 비활성 → source delay 빈 결과(null), sink lag는 snapshot 사용
        lenient().when(kafkaMetricsQuery.isEnabled()).thenReturn(false);
        lenient().when(snapshotStore.getRates("pipe.orders")).thenReturn(new RateResult(0.0, 0.0, 42L));

        PipelineStageStatusResponse res = service().stageStatus(wsId, principal, id);

        assertThat(res.overall()).isEqualTo("error");
        assertThat(res.bottleneck()).isEqualTo("SINK");
        assertThat(res.stages()).hasSize(2);

        PipelineStageStatusResponse.StageStatus source = res.stages().get(0);
        assertThat(source.stage()).isEqualTo("SOURCE");
        assertThat(source.connectorState()).isEqualTo("RUNNING");
        assertThat(source.status()).isEqualTo("OK");
        assertThat(source.delayMs()).isNull();

        PipelineStageStatusResponse.StageStatus sink = res.stages().get(1);
        assertThat(sink.stage()).isEqualTo("SINK");
        assertThat(sink.status()).isEqualTo("FAILED");
        assertThat(sink.error()).contains("PSQLException");
        assertThat(sink.lagMessages()).isEqualTo(42L);
    }

    private static ConnectorEntity connector(ConnectorKind kind, String state, String lastError) {
        ConnectorEntity c = new ConnectorEntity();
        c.setKind(kind);
        c.setState(state);
        c.setLastError(lastError);
        return c;
    }

    @Test
    void consumerGroupsPropagatesKafkaLookupFailure() {
        UUID wsId = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        AuthenticatedUser principal = new AuthenticatedUser(UUID.randomUUID(), wsId, "u@bifrost.io");

        PipelineEntity p = new PipelineEntity();
        p.setId(id);
        p.setTenantId(wsId);
        p.setPattern(PipelinePattern.FAN_OUT);
        p.setTopicName("eda.orders.events");
        when(pipelineRepository.findByIdAndTenantId(id, wsId)).thenReturn(Optional.of(p));

        AdminClient adminClient = mock(AdminClient.class);
        when(adminClient.listConsumerGroups()).thenThrow(new RuntimeException("kafka unavailable"));

        assertThatThrownBy(() -> service(adminClient).consumerGroups(wsId, principal, id))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Consumer group");
    }

    @Test
    void consumerGroupsPropagatesOffsetLookupFailureWhenEmptyCannotBeProved() throws Exception {
        UUID wsId = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        AuthenticatedUser principal = new AuthenticatedUser(UUID.randomUUID(), wsId, "u@bifrost.io");

        PipelineEntity p = new PipelineEntity();
        p.setId(id);
        p.setTenantId(wsId);
        p.setPattern(PipelinePattern.FAN_OUT);
        p.setTopicName("eda.orders.events");
        when(pipelineRepository.findByIdAndTenantId(id, wsId)).thenReturn(Optional.of(p));

        AdminClient adminClient = mock(AdminClient.class);
        ListConsumerGroupsResult groupsResult = mock(ListConsumerGroupsResult.class);
        when(adminClient.listConsumerGroups()).thenReturn(groupsResult);
        when(groupsResult.all()).thenReturn(KafkaFuture.completedFuture(List.of(new ConsumerGroupListing("orders-ui", false))));

        ListConsumerGroupOffsetsResult offsetsResult = mock(ListConsumerGroupOffsetsResult.class);
        when(adminClient.listConsumerGroupOffsets("orders-ui")).thenReturn(offsetsResult);
        @SuppressWarnings("unchecked")
        KafkaFuture<Map<org.apache.kafka.common.TopicPartition, org.apache.kafka.clients.consumer.OffsetAndMetadata>> offsetsFuture =
                mock(KafkaFuture.class);
        when(offsetsResult.partitionsToOffsetAndMetadata()).thenReturn(offsetsFuture);
        when(offsetsFuture.get(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any()))
                .thenThrow(new RuntimeException("offsets unavailable"));

        assertThatThrownBy(() -> service(adminClient).consumerGroups(wsId, principal, id))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Consumer group");
    }

    @Test
    void consumerGroupsPropagatesPartialOffsetLookupFailureInsteadOfReturningPartialData() throws Exception {
        UUID wsId = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        AuthenticatedUser principal = new AuthenticatedUser(UUID.randomUUID(), wsId, "u@bifrost.io");

        PipelineEntity p = new PipelineEntity();
        p.setId(id);
        p.setTenantId(wsId);
        p.setPattern(PipelinePattern.FAN_OUT);
        p.setTopicName("eda.orders.events");
        when(pipelineRepository.findByIdAndTenantId(id, wsId)).thenReturn(Optional.of(p));

        AdminClient adminClient = mock(AdminClient.class);
        ListConsumerGroupsResult groupsResult = mock(ListConsumerGroupsResult.class);
        when(adminClient.listConsumerGroups()).thenReturn(groupsResult);
        when(groupsResult.all()).thenReturn(KafkaFuture.completedFuture(List.of(
                new ConsumerGroupListing("orders-ui", false),
                new ConsumerGroupListing("orders-worker", false))));

        ListConsumerGroupOffsetsResult okOffsetsResult = mock(ListConsumerGroupOffsetsResult.class);
        when(adminClient.listConsumerGroupOffsets("orders-ui")).thenReturn(okOffsetsResult);
        when(okOffsetsResult.partitionsToOffsetAndMetadata()).thenReturn(KafkaFuture.completedFuture(
                Map.of(new TopicPartition("eda.orders.events", 0), new OffsetAndMetadata(10L))));

        ListConsumerGroupOffsetsResult failedOffsetsResult = mock(ListConsumerGroupOffsetsResult.class);
        when(adminClient.listConsumerGroupOffsets("orders-worker")).thenReturn(failedOffsetsResult);
        @SuppressWarnings("unchecked")
        KafkaFuture<Map<TopicPartition, OffsetAndMetadata>> failedOffsetsFuture = mock(KafkaFuture.class);
        when(failedOffsetsResult.partitionsToOffsetAndMetadata()).thenReturn(failedOffsetsFuture);
        when(failedOffsetsFuture.get(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any()))
                .thenThrow(new RuntimeException("offsets unavailable"));

        assertThatThrownBy(() -> service(adminClient).consumerGroups(wsId, principal, id))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Consumer group");
    }

    @Test
    void consumerGroupsUsesCommittedOffsetsForInactiveGroupLag() {
        UUID wsId = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        AuthenticatedUser principal = new AuthenticatedUser(UUID.randomUUID(), wsId, "u@bifrost.io");

        PipelineEntity p = new PipelineEntity();
        p.setId(id);
        p.setTenantId(wsId);
        p.setPattern(PipelinePattern.FAN_OUT);
        p.setTopicName("eda.orders.events");
        when(pipelineRepository.findByIdAndTenantId(id, wsId)).thenReturn(Optional.of(p));

        AdminClient adminClient = mock(AdminClient.class);
        ListConsumerGroupsResult groupsResult = mock(ListConsumerGroupsResult.class);
        when(adminClient.listConsumerGroups()).thenReturn(groupsResult);
        when(groupsResult.all()).thenReturn(KafkaFuture.completedFuture(List.of(new ConsumerGroupListing("orders-ui", false))));

        TopicPartition tp = new TopicPartition("eda.orders.events", 0);
        ListConsumerGroupOffsetsResult offsetsResult = mock(ListConsumerGroupOffsetsResult.class);
        when(adminClient.listConsumerGroupOffsets("orders-ui")).thenReturn(offsetsResult);
        when(offsetsResult.partitionsToOffsetAndMetadata()).thenReturn(KafkaFuture.completedFuture(Map.of(tp, new OffsetAndMetadata(10L))));

        DescribeConsumerGroupsResult descResult = mock(DescribeConsumerGroupsResult.class);
        when(adminClient.describeConsumerGroups(List.of("orders-ui"))).thenReturn(descResult);
        when(descResult.all()).thenReturn(KafkaFuture.completedFuture(Map.of(
                "orders-ui",
                new ConsumerGroupDescription("orders-ui", false, List.of(), "", ConsumerGroupState.EMPTY, Node.noNode()))));

        ListOffsetsResult listOffsetsResult = mock(ListOffsetsResult.class);
        when(adminClient.listOffsets(org.mockito.ArgumentMatchers.anyMap())).thenReturn(listOffsetsResult);
        when(listOffsetsResult.all()).thenReturn(KafkaFuture.completedFuture(Map.of(
                tp,
                new ListOffsetsResult.ListOffsetsResultInfo(25L, -1L, Optional.empty()))));

        var result = service(adminClient).consumerGroups(wsId, principal, id);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).totalLag()).isEqualTo(15L);
        assertThat(result.get(0).partitionOffsets()).singleElement().satisfies(offset -> {
            assertThat(offset.partition()).isEqualTo(0);
            assertThat(offset.committed()).isEqualTo(10L);
            assertThat(offset.endOffset()).isEqualTo(25L);
        });
    }

    /** #404: 60분 창을 요청해도 생성시각(5분 전)으로 클램프 → 메트릭 쿼리 시작이 생성시각 이후여야 한다. */
    @Test
    void eventDistributionClampsQueryStartToCreatedAt() {
        UUID wsId = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        AuthenticatedUser principal = new AuthenticatedUser(UUID.randomUUID(), wsId, "u@bifrost.io");
        long createdSec = System.currentTimeMillis() / 1000L - 300; // 5분 전 생성

        PipelineEntity p = new PipelineEntity();
        p.setId(id);
        p.setTenantId(wsId);
        p.setPattern(PipelinePattern.DIRECT);
        p.setSchemaName("public");
        p.setTableName("orders");
        p.setTopicName("cdc.table.team.shop-1234.public.orders");
        org.springframework.test.util.ReflectionTestUtils.setField(
                p, "createdAt", java.time.Instant.ofEpochSecond(createdSec));
        when(pipelineRepository.findByIdAndTenantId(id, wsId)).thenReturn(Optional.of(p));
        when(kafkaMetricsQuery.isEnabled()).thenReturn(true);
        when(kafkaMetricsQuery.eventCountSeries(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong())).thenReturn(java.util.Map.of());

        service().eventDistribution(wsId, principal, id, 60); // 60분 창 요청

        org.mockito.ArgumentCaptor<Long> startCap = org.mockito.ArgumentCaptor.forClass(Long.class);
        org.mockito.Mockito.verify(kafkaMetricsQuery, org.mockito.Mockito.atLeastOnce()).eventCountSeries(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                startCap.capture(), org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong());
        // 클램프가 없으면 (now-60분)-evStep로 한참 이전이어야 하지만, 생성시각으로 잘려 createdAt 근처여야 한다.
        assertThat(startCap.getValue()).isGreaterThanOrEqualTo(createdSec - 60);
    }
}
