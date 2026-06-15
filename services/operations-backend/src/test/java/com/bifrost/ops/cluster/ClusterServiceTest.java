package com.bifrost.ops.cluster;

import com.bifrost.ops.adapters.prometheus.PrometheusClient;
import com.bifrost.ops.cluster.dto.KafkaClusterResponse;
import com.bifrost.ops.monitoring.query.KafkaMetricsQuery;
import com.bifrost.ops.pipeline.dto.ThroughputPoint;
import com.bifrost.ops.pipeline.persistence.repository.PipelineRepository;
import com.bifrost.ops.provisioning.persistence.repository.ConnectorRepository;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.DescribeClusterResult;
import org.apache.kafka.clients.admin.DescribeLogDirsResult;
import org.apache.kafka.clients.admin.DescribeTopicsResult;
import org.apache.kafka.clients.admin.ListTopicsResult;
import org.apache.kafka.clients.admin.LogDirDescription;
import org.apache.kafka.clients.admin.ReplicaInfo;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.TopicPartitionInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClusterServiceTest {

    private static final long ADMIN_TIMEOUT_SEC = 5L;

    @Mock private AdminClient admin;
    @Mock private PrometheusClient prometheus;
    @Mock private KafkaMetricsQuery kafkaMetricsQuery;
    @Mock private KubernetesClient k8s;
    @Mock private ConnectorRepository connectorRepository;
    @Mock private PipelineRepository pipelineRepository;

    private ClusterService service() {
        return new ClusterService(admin, prometheus, kafkaMetricsQuery, k8s,
                connectorRepository, pipelineRepository,
                "platform-kafka", "platform-connect", "http://connect.invalid");
    }

    @Test
    void throughputFallsBackToKafkaExporterMetricsWhenJmxAbsent() {
        // (#728) 배포환경 Prometheus는 JMX broker 메트릭이 없어 throughput이 빈 시리즈였다.
        // 쿼리에 kafka-exporter 폴백(topic/consumergroup offset rate)이 포함되어 시리즈가 채워져야 한다.
        when(kafkaMetricsQuery.isEnabled()).thenReturn(true);
        when(prometheus.queryRange(contains("kafka_topic_partition_current_offset"), anyLong(), anyLong(), anyLong()))
                .thenReturn(Map.of(1000L, 12.0));
        when(prometheus.queryRange(contains("kafka_consumergroup_current_offset"), anyLong(), anyLong(), anyLong()))
                .thenReturn(Map.of(1000L, 7.0));

        List<ThroughputPoint> series = service().throughput(30);

        assertThat(series).hasSize(1);
        assertThat(series.get(0).produceRate()).isEqualTo(12.0);
        assertThat(series.get(0).consumeRate()).isEqualTo(7.0);
        // JMX 1순위 + exporter 폴백이 한 쿼리에 모두 들어간다.
        verify(prometheus).queryRange(
                contains("kafka_server_brokertopicmetrics_messagesin_total"), anyLong(), anyLong(), anyLong());
    }

    @Test
    void kafkaClusterUsesFiveSecondAdminTimeouts() throws Exception {
        Node broker = new Node(1, "broker-1", 9092);
        ClusterFutures clusterFutures = stubCluster(broker);

        ListTopicsResult listTopicsResult = mock(ListTopicsResult.class);
        @SuppressWarnings("unchecked")
        KafkaFuture<Set<String>> topicNamesFuture = mock(KafkaFuture.class);
        when(admin.listTopics()).thenReturn(listTopicsResult);
        when(listTopicsResult.names()).thenReturn(topicNamesFuture);
        when(topicNamesFuture.get(ADMIN_TIMEOUT_SEC, TimeUnit.SECONDS)).thenReturn(Set.of("orders"));

        DescribeTopicsResult describeTopicsResult = mock(DescribeTopicsResult.class);
        @SuppressWarnings("unchecked")
        KafkaFuture<Map<String, TopicDescription>> descriptionsFuture = mock(KafkaFuture.class);
        TopicDescription orders = new TopicDescription("orders", false, List.of(
                new TopicPartitionInfo(0, broker, List.of(broker), List.of(broker))));
        when(admin.describeTopics(Set.of("orders"))).thenReturn(describeTopicsResult);
        when(describeTopicsResult.allTopicNames()).thenReturn(descriptionsFuture);
        when(descriptionsFuture.get(ADMIN_TIMEOUT_SEC, TimeUnit.SECONDS))
                .thenReturn(Map.of("orders", orders));

        DescribeLogDirsResult logDirsResult = mock(DescribeLogDirsResult.class);
        @SuppressWarnings("unchecked")
        KafkaFuture<Map<Integer, Map<String, LogDirDescription>>> logDirsFuture = mock(KafkaFuture.class);
        LogDirDescription logDir = new LogDirDescription(null, Map.of(
                new TopicPartition("orders", 0), new ReplicaInfo(42L, 0L, false)));
        when(admin.describeLogDirs(List.of(1))).thenReturn(logDirsResult);
        when(logDirsResult.allDescriptions()).thenReturn(logDirsFuture);
        when(logDirsFuture.get(ADMIN_TIMEOUT_SEC, TimeUnit.SECONDS))
                .thenReturn(Map.of(1, Map.of("/var/lib/kafka", logDir)));

        KafkaClusterResponse response = service().kafkaCluster();

        assertThat(response.controllerId()).isEqualTo(1);
        assertThat(response.brokerCount()).isEqualTo(1);
        assertThat(response.totalPartitions()).isEqualTo(1);
        assertThat(response.underReplicated()).isZero();
        assertThat(response.offlinePartitions()).isZero();
        assertThat(response.status()).isEqualTo("healthy");
        assertThat(response.message()).isNull();
        assertThat(response.brokers()).singleElement().satisfies(info -> {
            assertThat(info.id()).isEqualTo(1);
            assertThat(info.controller()).isTrue();
            assertThat(info.leaderPartitions()).isEqualTo(1L);
            assertThat(info.logDirBytes()).isEqualTo(42L);
        });

        verify(clusterFutures.nodes()).get(ADMIN_TIMEOUT_SEC, TimeUnit.SECONDS);
        verify(clusterFutures.controller()).get(ADMIN_TIMEOUT_SEC, TimeUnit.SECONDS);
        verify(topicNamesFuture).get(ADMIN_TIMEOUT_SEC, TimeUnit.SECONDS);
        verify(descriptionsFuture).get(ADMIN_TIMEOUT_SEC, TimeUnit.SECONDS);
        verify(logDirsFuture).get(ADMIN_TIMEOUT_SEC, TimeUnit.SECONDS);
    }

    @Test
    void kafkaClusterReturnsBrokerPartialResponseWhenTopicLookupTimesOut() throws Exception {
        Node broker = new Node(1, "broker-1", 9092);
        stubCluster(broker);

        ListTopicsResult listTopicsResult = mock(ListTopicsResult.class);
        @SuppressWarnings("unchecked")
        KafkaFuture<Set<String>> topicNamesFuture = mock(KafkaFuture.class);
        when(admin.listTopics()).thenReturn(listTopicsResult);
        when(listTopicsResult.names()).thenReturn(topicNamesFuture);
        when(topicNamesFuture.get(ADMIN_TIMEOUT_SEC, TimeUnit.SECONDS))
                .thenThrow(new TimeoutException("topic lookup timed out"));

        stubEmptyLogDirs(1);

        KafkaClusterResponse response = service().kafkaCluster();

        assertThat(response.controllerId()).isEqualTo(1);
        assertThat(response.brokerCount()).isEqualTo(1);
        assertThat(response.totalPartitions()).isZero();
        assertThat(response.underReplicated()).isZero();
        assertThat(response.offlinePartitions()).isZero();
        assertThat(response.status()).isEqualTo("warning");
        assertThat(response.message()).isEqualTo("topic metadata unavailable");
        assertThat(response.brokers()).singleElement().satisfies(info -> {
            assertThat(info.id()).isEqualTo(1);
            assertThat(info.leaderPartitions()).isZero();
            assertThat(info.logDirBytes()).isZero();
        });
        verify(topicNamesFuture).get(ADMIN_TIMEOUT_SEC, TimeUnit.SECONDS);
        verify(admin, never()).describeTopics(anyCollection());
    }

    @Test
    void kafkaClusterReturnsBrokerPartialResponseWhenTopicDescriptionTimesOut() throws Exception {
        Node broker = new Node(1, "broker-1", 9092);
        stubCluster(broker);

        ListTopicsResult listTopicsResult = mock(ListTopicsResult.class);
        @SuppressWarnings("unchecked")
        KafkaFuture<Set<String>> topicNamesFuture = mock(KafkaFuture.class);
        when(admin.listTopics()).thenReturn(listTopicsResult);
        when(listTopicsResult.names()).thenReturn(topicNamesFuture);
        when(topicNamesFuture.get(ADMIN_TIMEOUT_SEC, TimeUnit.SECONDS)).thenReturn(Set.of("orders"));

        DescribeTopicsResult describeTopicsResult = mock(DescribeTopicsResult.class);
        @SuppressWarnings("unchecked")
        KafkaFuture<Map<String, TopicDescription>> descriptionsFuture = mock(KafkaFuture.class);
        when(admin.describeTopics(Set.of("orders"))).thenReturn(describeTopicsResult);
        when(describeTopicsResult.allTopicNames()).thenReturn(descriptionsFuture);
        when(descriptionsFuture.get(ADMIN_TIMEOUT_SEC, TimeUnit.SECONDS))
                .thenThrow(new TimeoutException("topic description timed out"));

        stubEmptyLogDirs(1);

        KafkaClusterResponse response = service().kafkaCluster();

        assertThat(response.controllerId()).isEqualTo(1);
        assertThat(response.brokerCount()).isEqualTo(1);
        assertThat(response.totalPartitions()).isZero();
        assertThat(response.status()).isEqualTo("warning");
        assertThat(response.message()).isEqualTo("topic metadata unavailable");
        assertThat(response.brokers()).singleElement().satisfies(info -> {
            assertThat(info.id()).isEqualTo(1);
            assertThat(info.leaderPartitions()).isZero();
            assertThat(info.logDirBytes()).isZero();
        });
        verify(topicNamesFuture).get(ADMIN_TIMEOUT_SEC, TimeUnit.SECONDS);
        verify(descriptionsFuture).get(ADMIN_TIMEOUT_SEC, TimeUnit.SECONDS);
    }

    @Test
    void kafkaClusterKeepsTopicMetricsWhenLogDirLookupTimesOut() throws Exception {
        Node broker = new Node(1, "broker-1", 9092);
        stubCluster(broker);

        ListTopicsResult listTopicsResult = mock(ListTopicsResult.class);
        @SuppressWarnings("unchecked")
        KafkaFuture<Set<String>> topicNamesFuture = mock(KafkaFuture.class);
        when(admin.listTopics()).thenReturn(listTopicsResult);
        when(listTopicsResult.names()).thenReturn(topicNamesFuture);
        when(topicNamesFuture.get(ADMIN_TIMEOUT_SEC, TimeUnit.SECONDS)).thenReturn(Set.of("orders"));

        DescribeTopicsResult describeTopicsResult = mock(DescribeTopicsResult.class);
        @SuppressWarnings("unchecked")
        KafkaFuture<Map<String, TopicDescription>> descriptionsFuture = mock(KafkaFuture.class);
        TopicDescription orders = new TopicDescription("orders", false, List.of(
                new TopicPartitionInfo(0, broker, List.of(broker), List.of(broker))));
        when(admin.describeTopics(Set.of("orders"))).thenReturn(describeTopicsResult);
        when(describeTopicsResult.allTopicNames()).thenReturn(descriptionsFuture);
        when(descriptionsFuture.get(ADMIN_TIMEOUT_SEC, TimeUnit.SECONDS))
                .thenReturn(Map.of("orders", orders));

        DescribeLogDirsResult logDirsResult = mock(DescribeLogDirsResult.class);
        @SuppressWarnings("unchecked")
        KafkaFuture<Map<Integer, Map<String, LogDirDescription>>> logDirsFuture = mock(KafkaFuture.class);
        when(admin.describeLogDirs(List.of(1))).thenReturn(logDirsResult);
        when(logDirsResult.allDescriptions()).thenReturn(logDirsFuture);
        when(logDirsFuture.get(ADMIN_TIMEOUT_SEC, TimeUnit.SECONDS))
                .thenThrow(new TimeoutException("log dir lookup timed out"));

        KafkaClusterResponse response = service().kafkaCluster();

        assertThat(response.controllerId()).isEqualTo(1);
        assertThat(response.brokerCount()).isEqualTo(1);
        assertThat(response.totalPartitions()).isEqualTo(1);
        assertThat(response.status()).isEqualTo("warning");
        assertThat(response.message()).isEqualTo("log dirs unavailable");
        assertThat(response.brokers()).singleElement().satisfies(info -> {
            assertThat(info.id()).isEqualTo(1);
            assertThat(info.leaderPartitions()).isEqualTo(1L);
            assertThat(info.logDirBytes()).isZero();
        });
        verify(logDirsFuture).get(ADMIN_TIMEOUT_SEC, TimeUnit.SECONDS);
    }

    @Test
    void kafkaClusterReturnsBrokersWhenControllerLookupTimesOut() throws Exception {
        Node broker = new Node(1, "broker-1", 9092);
        DescribeClusterResult clusterResult = mock(DescribeClusterResult.class);
        @SuppressWarnings("unchecked")
        KafkaFuture<Collection<Node>> nodesFuture = mock(KafkaFuture.class);
        @SuppressWarnings("unchecked")
        KafkaFuture<Node> controllerFuture = mock(KafkaFuture.class);
        when(admin.describeCluster()).thenReturn(clusterResult);
        when(clusterResult.nodes()).thenReturn(nodesFuture);
        when(clusterResult.controller()).thenReturn(controllerFuture);
        when(nodesFuture.get(ADMIN_TIMEOUT_SEC, TimeUnit.SECONDS)).thenReturn(List.of(broker));
        when(controllerFuture.get(ADMIN_TIMEOUT_SEC, TimeUnit.SECONDS))
                .thenThrow(new TimeoutException("controller lookup timed out"));

        ListTopicsResult listTopicsResult = mock(ListTopicsResult.class);
        @SuppressWarnings("unchecked")
        KafkaFuture<Set<String>> topicNamesFuture = mock(KafkaFuture.class);
        when(admin.listTopics()).thenReturn(listTopicsResult);
        when(listTopicsResult.names()).thenReturn(topicNamesFuture);
        when(topicNamesFuture.get(ADMIN_TIMEOUT_SEC, TimeUnit.SECONDS)).thenReturn(Set.of());

        stubEmptyLogDirs(1);

        KafkaClusterResponse response = service().kafkaCluster();

        assertThat(response.controllerId()).isEqualTo(-1);
        assertThat(response.brokerCount()).isEqualTo(1);
        assertThat(response.status()).isEqualTo("warning");
        assertThat(response.message()).isEqualTo("controller unavailable");
        assertThat(response.brokers()).singleElement().satisfies(info -> {
            assertThat(info.id()).isEqualTo(1);
            assertThat(info.controller()).isFalse();
        });
        verify(controllerFuture).get(ADMIN_TIMEOUT_SEC, TimeUnit.SECONDS);
    }

    @Test
    void kafkaClusterReturnsEmptyResponseWhenBrokerLookupTimesOut() throws Exception {
        DescribeClusterResult clusterResult = mock(DescribeClusterResult.class);
        @SuppressWarnings("unchecked")
        KafkaFuture<Collection<Node>> nodesFuture = mock(KafkaFuture.class);
        when(admin.describeCluster()).thenReturn(clusterResult);
        when(clusterResult.nodes()).thenReturn(nodesFuture);
        when(nodesFuture.get(ADMIN_TIMEOUT_SEC, TimeUnit.SECONDS))
                .thenThrow(new TimeoutException("broker lookup timed out"));

        KafkaClusterResponse response = service().kafkaCluster();

        assertThat(response.controllerId()).isEqualTo(-1);
        assertThat(response.brokerCount()).isZero();
        assertThat(response.status()).isEqualTo("error");
        assertThat(response.message()).isEqualTo("cluster metadata unavailable");
        assertThat(response.brokers()).isEmpty();
        verify(nodesFuture).get(ADMIN_TIMEOUT_SEC, TimeUnit.SECONDS);
        verify(admin, never()).listTopics();
    }

    private ClusterFutures stubCluster(Node broker) throws Exception {
        DescribeClusterResult clusterResult = mock(DescribeClusterResult.class);
        @SuppressWarnings("unchecked")
        KafkaFuture<Collection<Node>> nodesFuture = mock(KafkaFuture.class);
        @SuppressWarnings("unchecked")
        KafkaFuture<Node> controllerFuture = mock(KafkaFuture.class);
        when(admin.describeCluster()).thenReturn(clusterResult);
        when(clusterResult.nodes()).thenReturn(nodesFuture);
        when(clusterResult.controller()).thenReturn(controllerFuture);
        when(nodesFuture.get(ADMIN_TIMEOUT_SEC, TimeUnit.SECONDS)).thenReturn(List.of(broker));
        when(controllerFuture.get(ADMIN_TIMEOUT_SEC, TimeUnit.SECONDS)).thenReturn(broker);
        return new ClusterFutures(nodesFuture, controllerFuture);
    }

    private void stubEmptyLogDirs(int brokerId) throws Exception {
        DescribeLogDirsResult logDirsResult = mock(DescribeLogDirsResult.class);
        @SuppressWarnings("unchecked")
        KafkaFuture<Map<Integer, Map<String, LogDirDescription>>> logDirsFuture = mock(KafkaFuture.class);
        when(admin.describeLogDirs(List.of(brokerId))).thenReturn(logDirsResult);
        when(logDirsResult.allDescriptions()).thenReturn(logDirsFuture);
        when(logDirsFuture.get(ADMIN_TIMEOUT_SEC, TimeUnit.SECONDS)).thenReturn(Map.of());
    }

    private record ClusterFutures(
            KafkaFuture<Collection<Node>> nodes,
            KafkaFuture<Node> controller) {
    }
}
