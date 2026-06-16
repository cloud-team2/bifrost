package com.bifrost.ops.internalops.controller;

import com.bifrost.ops.global.common.error.ApiException;
import com.bifrost.ops.global.common.error.ErrorCode;
import com.bifrost.ops.internalops.AgentHeaders;
import com.bifrost.ops.internalops.WorkspaceLookup;
import com.bifrost.ops.internalops.dto.ClusterInfoResult;
import com.bifrost.ops.internalops.dto.OpsEnvelope;
import com.bifrost.ops.pipeline.persistence.repository.PipelineRepository;
import com.bifrost.ops.workspace.persistence.entity.WorkspaceEntity;
import com.bifrost.ops.workspace.persistence.repository.WorkspaceRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.DescribeClusterResult;
import org.apache.kafka.clients.admin.DescribeTopicsResult;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartitionInfo;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Agent read 프리미티브 — Kafka 클러스터/브로커/토픽·파티션 상세(#633).
 *
 * <p>특정 도구로 일일이 노출하는 대신, 클러스터 전반(브로커 목록·컨트롤러)과 프로젝트 토픽의
 * 파티션 헬스(leader·replicas·ISR·under-replicated·offline)를 한 번에 제공해 에이전트가
 * '클러스터/브로커 현황'을 폭넓게 답하게 한다. read-only(AdminClient describe).
 */
@RestController
@RequestMapping("/internal/ops/projects/{projectId}/kafka/cluster")
public class InternalOpsKafkaController {

    private static final long TIMEOUT_SEC = 5L;

    private final AdminClient adminClient;
    private final WorkspaceRepository workspaceRepository;
    private final PipelineRepository pipelineRepository;

    public InternalOpsKafkaController(AdminClient adminClient,
                                      WorkspaceRepository workspaceRepository,
                                      PipelineRepository pipelineRepository) {
        this.adminClient = adminClient;
        this.workspaceRepository = workspaceRepository;
        this.pipelineRepository = pipelineRepository;
    }

    /** get_cluster_info — 브로커·컨트롤러 + 프로젝트 토픽 파티션 상세. */
    @GetMapping
    public ResponseEntity<OpsEnvelope<ClusterInfoResult>> clusterInfo(
            @PathVariable String projectId,
            HttpServletRequest request) {
        String requestId = AgentHeaders.requestId(request);
        try {
            DescribeClusterResult dc = adminClient.describeCluster();
            String clusterId = dc.clusterId().get(TIMEOUT_SEC, TimeUnit.SECONDS);
            Node controller = dc.controller().get(TIMEOUT_SEC, TimeUnit.SECONDS);
            List<Node> nodes = new ArrayList<>(dc.nodes().get(TIMEOUT_SEC, TimeUnit.SECONDS));
            Integer controllerId = controller == null ? null : controller.id();

            List<ClusterInfoResult.BrokerInfo> brokers = nodes.stream()
                    .map(n -> new ClusterInfoResult.BrokerInfo(
                            n.id(), n.host(), n.port(),
                            controllerId != null && controllerId == n.id()))
                    .toList();

            List<ClusterInfoResult.TopicInfo> topics = describeProjectTopics(projectId);

            return ResponseEntity.ok(OpsEnvelope.ok(requestId, "get_cluster_info",
                    new ClusterInfoResult(clusterId, controllerId, brokers.size(), brokers, topics)));
        } catch (ApiException e) {
            return ResponseEntity.ok(OpsEnvelope.error(requestId, "get_cluster_info", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.ok(OpsEnvelope.error(requestId, "get_cluster_info",
                    "클러스터 정보 조회 실패: " + e.getMessage()));
        }
    }

    private List<ClusterInfoResult.TopicInfo> describeProjectTopics(String projectId) throws Exception {
        WorkspaceEntity ws = WorkspaceLookup.resolve(workspaceRepository, projectId)
                .orElseThrow(() -> new ApiException(ErrorCode.WORKSPACE_NOT_FOUND,
                        "프로젝트를 찾을 수 없습니다: " + projectId));
        Set<String> topicNames = pipelineRepository.findByTenantIdOrderByCreatedAtDesc(ws.getId()).stream()
                .map(p -> p.getTopicName())
                .filter(t -> t != null && !t.isBlank())
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        if (topicNames.isEmpty()) return List.of();

        DescribeTopicsResult result = adminClient.describeTopics(topicNames);
        Map<String, KafkaFuture<TopicDescription>> described = result.topicNameValues();

        List<ClusterInfoResult.TopicInfo> out = new ArrayList<>();
        for (String name : topicNames) {
            TopicDescription d = topicDescriptionOrNull(described.get(name));
            if (d == null) continue;
            int under = 0, offline = 0, rf = 0;
            List<ClusterInfoResult.PartitionInfo> parts = new ArrayList<>();
            for (TopicPartitionInfo part : d.partitions()) {
                rf = Math.max(rf, part.replicas().size());
                if (part.leader() == null) offline++;
                else if (part.isr().size() < part.replicas().size()) under++;
                parts.add(new ClusterInfoResult.PartitionInfo(
                        part.partition(),
                        part.leader() == null ? null : part.leader().id(),
                        part.replicas().stream().map(Node::id).toList(),
                        part.isr().stream().map(Node::id).toList()));
            }
            out.add(new ClusterInfoResult.TopicInfo(name, d.partitions().size(), rf, under, offline, parts));
        }
        return out;
    }

    private TopicDescription topicDescriptionOrNull(KafkaFuture<TopicDescription> future)
            throws InterruptedException, ExecutionException, java.util.concurrent.TimeoutException {
        if (future == null) return null;
        try {
            return future.get(TIMEOUT_SEC, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof UnknownTopicOrPartitionException) return null;
            throw e;
        }
    }
}
