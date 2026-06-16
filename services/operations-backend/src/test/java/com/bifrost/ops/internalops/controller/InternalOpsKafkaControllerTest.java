package com.bifrost.ops.internalops.controller;

import com.bifrost.ops.internalops.dto.ClusterInfoResult;
import com.bifrost.ops.internalops.dto.OpsEnvelope;
import com.bifrost.ops.pipeline.persistence.entity.PipelineEntity;
import com.bifrost.ops.pipeline.persistence.repository.PipelineRepository;
import com.bifrost.ops.workspace.persistence.entity.WorkspaceEntity;
import com.bifrost.ops.workspace.persistence.repository.WorkspaceRepository;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.DescribeClusterResult;
import org.apache.kafka.clients.admin.DescribeTopicsResult;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartitionInfo;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InternalOpsKafkaControllerTest {

    private static final String PROJECT_ID = "proj-001";

    private final AdminClient adminClient = mock(AdminClient.class);
    private final WorkspaceRepository workspaceRepository = mock(WorkspaceRepository.class);
    private final PipelineRepository pipelineRepository = mock(PipelineRepository.class);
    private final InternalOpsKafkaController controller =
            new InternalOpsKafkaController(adminClient, workspaceRepository, pipelineRepository);

    @Test
    void clusterInfoSkipsUnknownTopicAndReturnsPartialMetadata() throws Exception {
        UUID tenantId = UUID.randomUUID();
        Node broker = new Node(1, "broker-1", 9092);
        stubCluster(broker);
        when(workspaceRepository.findByNamespace(PROJECT_ID)).thenReturn(Optional.of(workspace(tenantId)));
        when(pipelineRepository.findByTenantIdOrderByCreatedAtDesc(tenantId))
                .thenReturn(List.of(pipeline(tenantId, "orders.cdc"), pipeline(tenantId, "missing.cdc")));

        DescribeTopicsResult describeTopics = mock(DescribeTopicsResult.class);
        @SuppressWarnings("unchecked")
        KafkaFuture<TopicDescription> missingTopic = mock(KafkaFuture.class);
        when(adminClient.describeTopics(anyCollection())).thenReturn(describeTopics);
        when(describeTopics.topicNameValues()).thenReturn(Map.of(
                "orders.cdc", KafkaFuture.completedFuture(topic("orders.cdc", broker)),
                "missing.cdc", missingTopic));
        when(missingTopic.get(anyLong(), eq(TimeUnit.SECONDS)))
                .thenThrow(new ExecutionException(new UnknownTopicOrPartitionException("missing.cdc")));

        ResponseEntity<OpsEnvelope<ClusterInfoResult>> response =
                controller.clusterInfo(PROJECT_ID, request());

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().ok()).isTrue();
        assertThat(response.getBody().operation()).isEqualTo("get_cluster_info");
        assertThat(response.getBody().result().topics()).singleElement().satisfies(topic -> {
            assertThat(topic.name()).isEqualTo("orders.cdc");
            assertThat(topic.partitionCount()).isEqualTo(1);
            assertThat(topic.replicationFactor()).isEqualTo(1);
            assertThat(topic.partitions()).singleElement().satisfies(partition -> {
                assertThat(partition.partition()).isZero();
                assertThat(partition.leader()).isEqualTo(1);
                assertThat(partition.replicas()).containsExactly(1);
                assertThat(partition.isr()).containsExactly(1);
            });
        });
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> describedTopics =
                ArgumentCaptor.forClass(Collection.class);
        verify(adminClient).describeTopics(describedTopics.capture());
        assertThat(describedTopics.getValue()).containsExactly("orders.cdc", "missing.cdc");
    }

    private void stubCluster(Node broker) throws Exception {
        DescribeClusterResult cluster = mock(DescribeClusterResult.class);
        when(adminClient.describeCluster()).thenReturn(cluster);
        when(cluster.clusterId()).thenReturn(KafkaFuture.completedFuture("cluster-1"));
        when(cluster.controller()).thenReturn(KafkaFuture.completedFuture(broker));
        when(cluster.nodes()).thenReturn(KafkaFuture.completedFuture(List.of(broker)));
    }

    private static TopicDescription topic(String name, Node broker) {
        return new TopicDescription(name, false, List.of(
                new TopicPartitionInfo(0, broker, List.of(broker), List.of(broker))));
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Request-Id", "req-cluster-001");
        return request;
    }

    private static WorkspaceEntity workspace(UUID tenantId) {
        WorkspaceEntity workspace = new WorkspaceEntity();
        workspace.setId(tenantId);
        workspace.setName("Project");
        workspace.setNamespace(PROJECT_ID);
        workspace.setStatus(WorkspaceEntity.Status.ACTIVE);
        return workspace;
    }

    private static PipelineEntity pipeline(UUID tenantId, String topicName) {
        PipelineEntity pipeline = new PipelineEntity();
        pipeline.setId(UUID.randomUUID());
        pipeline.setTenantId(tenantId);
        pipeline.setName(topicName);
        pipeline.setTopicName(topicName);
        return pipeline;
    }
}
