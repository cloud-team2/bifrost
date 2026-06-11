package com.bifrost.ops.pipeline.service;

import com.bifrost.ops.auth.jwt.AuthenticatedUser;
import com.bifrost.ops.global.common.error.ApiException;
import com.bifrost.ops.global.common.error.ErrorCode;
import com.bifrost.ops.internalops.dto.TraceSummaryResult;
import com.bifrost.ops.monitoring.query.KafkaMetricsQuery;
import com.bifrost.ops.monitoring.query.TraceQuery;
import com.bifrost.ops.pipeline.dto.ConsumerGroupInfo;
import com.bifrost.ops.pipeline.dto.EventDistPoint;
import com.bifrost.ops.pipeline.dto.MetricPoint;
import com.bifrost.ops.pipeline.dto.PipelineMetricsResponse;
import com.bifrost.ops.pipeline.dto.PipelineStageStatusResponse;
import com.bifrost.ops.pipeline.dto.TopicInfoResponse;
import com.bifrost.ops.pipeline.dto.ThroughputPoint;
import com.bifrost.ops.pipeline.kafka.OffsetSnapshotStore;
import com.bifrost.ops.pipeline.kafka.RateResult;
import com.bifrost.ops.pipeline.persistence.entity.PipelineEntity;
import com.bifrost.ops.pipeline.persistence.repository.PipelineRepository;
import com.bifrost.ops.provisioning.dto.ConnectorKind;
import com.bifrost.ops.provisioning.dto.PipelinePattern;
import com.bifrost.ops.provisioning.naming.ConnectorNaming;
import com.bifrost.ops.provisioning.persistence.entity.ConnectorEntity;
import com.bifrost.ops.provisioning.persistence.repository.ConnectorRepository;
import com.bifrost.ops.workspace.WorkspaceAccessGuard;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.Config;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.admin.ConsumerGroupListing;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.MemberDescription;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.TopicPartitionInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 파이프라인 토픽/컨슈머 그룹/메트릭 조회(#126). KafkaAdmin(AdminClient) 기반.
 * Kafka에 접근 불가한 환경(로컬 포트포워드 미설정 등)에서는 빈 결과를 반환한다.
 */
@Service
public class PipelineTopicService {

    private static final Logger log = LoggerFactory.getLogger(PipelineTopicService.class);
    private static final long ADMIN_TIMEOUT_SEC = 5L;

    private final PipelineRepository pipelineRepository;
    private final ConnectorRepository connectorRepository;
    private final WorkspaceAccessGuard accessGuard;
    private final AdminClient adminClient;
    private final OffsetSnapshotStore snapshotStore;
    private final KafkaMetricsQuery kafkaMetricsQuery;
    private final TraceQuery traceQuery;

    public PipelineTopicService(PipelineRepository pipelineRepository,
                                ConnectorRepository connectorRepository,
                                WorkspaceAccessGuard accessGuard,
                                AdminClient adminClient,
                                OffsetSnapshotStore snapshotStore,
                                KafkaMetricsQuery kafkaMetricsQuery,
                                TraceQuery traceQuery) {
        this.pipelineRepository = pipelineRepository;
        this.connectorRepository = connectorRepository;
        this.accessGuard = accessGuard;
        this.adminClient = adminClient;
        this.snapshotStore = snapshotStore;
        this.kafkaMetricsQuery = kafkaMetricsQuery;
        this.traceQuery = traceQuery;
    }

    public TopicInfoResponse topicInfo(UUID wsId, AuthenticatedUser principal, UUID id) {
        PipelineEntity p = loadPipeline(wsId, principal, id);
        String topic = p.getTopicName();
        if (topic == null || topic.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "파이프라인에 토픽이 없습니다");
        }
        try {
            return fetchTopicInfo(topic);
        } catch (Exception e) {
            log.warn("토픽 정보 조회 실패 (Kafka 접근 불가): topic={}, cause={}", topic, e.getMessage());
            return new TopicInfoResponse(topic, 100.0, -1L, List.of());
        }
    }

    public List<ConsumerGroupInfo> consumerGroups(UUID wsId, AuthenticatedUser principal, UUID id) {
        PipelineEntity p = loadPipeline(wsId, principal, id);
        String topic = p.getTopicName();
        if (topic == null || topic.isBlank()) return List.of();
        try {
            return fetchConsumerGroups(topic);
        } catch (Exception e) {
            log.warn("Consumer group 조회 실패: topic={}, cause={}", topic, e.getMessage());
            return List.of();
        }
    }

    public PipelineMetricsResponse metrics(UUID wsId, AuthenticatedUser principal, UUID id) {
        PipelineEntity p = loadPipeline(wsId, principal, id);

        // error_pct: connectors 테이블 기반 (Kafka 불필요, 즉시)
        List<ConnectorEntity> connectors = connectorRepository.findByPipelineId(id);
        double errorPct = 0.0;
        if (!connectors.isEmpty()) {
            long failed = connectors.stream()
                    .filter(c -> "FAILED".equals(c.getState()) || "PARTIALLY_FAILED".equals(c.getState()))
                    .count();
            errorPct = (double) failed / connectors.size() * 100.0;
        }

        // produce/consume rate + lag: Prometheus 우선, 실패 시 OffsetSnapshot fallback
        String topic = p.getTopicName();
        if (topic != null && !topic.isBlank()) {
            if (kafkaMetricsQuery.isEnabled()) {
                try {
                    double produceRate = kafkaMetricsQuery.produceRate(topic);
                    double consumeRate = kafkaMetricsQuery.consumeRate(topic);
                    long lag = kafkaMetricsQuery.totalLag(sinkConsumerGroup(id));
                    return new PipelineMetricsResponse(produceRate, consumeRate, lag, errorPct);
                } catch (Exception e) {
                    log.warn("Prometheus 조회 실패, 스냅샷 fallback: topic={}, cause={}", topic, e.getMessage());
                }
            }
            RateResult rates = snapshotStore.getRates(topic);
            return new PipelineMetricsResponse(
                    rates.produceRate(), rates.consumeRate(), rates.lagMessages(), errorPct);
        }

        return new PipelineMetricsResponse(0.0, 0.0, 0L, errorPct);
    }

    /** 파이프라인 분산 trace 요약(#498, Tracing 탭). traceId 지정 시 그 trace, 없으면 최근. */
    public TraceSummaryResult trace(UUID wsId, AuthenticatedUser principal, UUID id, String traceId) {
        PipelineEntity p = loadPipeline(wsId, principal, id);
        UUID pipelineId = p.getId();
        String connector = ConnectorNaming.sourceConnectorName(pipelineId);
        return (traceId == null || traceId.isBlank())
                ? traceQuery.query(connector, null)
                : traceQuery.queryById(connector, traceId);
    }

    /**
     * 처리량 추이(#126). Prometheus range query로 produce/consume rate 시계열을 만든다.
     * Prometheus 비활성·실패·토픽 없음이면 빈 목록(프론트가 적절히 처리).
     */
    public List<ThroughputPoint> throughput(UUID wsId, AuthenticatedUser principal, UUID id, int minutes) {
        PipelineEntity p = loadPipeline(wsId, principal, id);
        String topic = p.getTopicName();
        if (topic == null || topic.isBlank() || !kafkaMetricsQuery.isEnabled()) return List.of();

        long endSec = System.currentTimeMillis() / 1000L;
        long startSec = clampStart(endSec - Math.max(1, minutes) * 60L, p);
        long stepSec = stepFor(minutes);
        try {
            Map<Long, Double> produce = kafkaMetricsQuery.produceSeries(topic, startSec, endSec, stepSec);
            Map<Long, Double> consume = kafkaMetricsQuery.consumeSeries(topic, startSec, endSec, stepSec);
            // produce 타임스탬프 기준으로 정렬·병합 (둘 다 동일 step이라 키 정렬됨)
            java.util.TreeSet<Long> stamps = new java.util.TreeSet<>();
            stamps.addAll(produce.keySet());
            stamps.addAll(consume.keySet());
            List<ThroughputPoint> out = new ArrayList<>(stamps.size());
            for (Long ts : stamps) {
                out.add(new ThroughputPoint(
                        ts * 1000L,
                        produce.getOrDefault(ts, 0.0),
                        consume.getOrDefault(ts, 0.0)));
            }
            return out;
        } catch (Exception e) {
            log.warn("처리량 추이 조회 실패: topic={}, cause={}", topic, e.getMessage());
            return List.of();
        }
    }

    /** 소스 지연(ms) 추이(#126, Sync 탭). Debezium MilliSecondsBehindSource. */
    public List<MetricPoint> sourceDelay(UUID wsId, AuthenticatedUser principal, UUID id, int minutes) {
        PipelineEntity p = loadPipeline(wsId, principal, id);
        String server = debeziumServer(p);
        if (server == null || !kafkaMetricsQuery.isEnabled()) return List.of();
        long[] win = window(minutes);
        try {
            return toPoints(kafkaMetricsQuery.sourceDelaySeries(server, clampStart(win[0], p), win[1], win[2]));
        } catch (Exception e) {
            log.warn("소스 지연 추이 조회 실패: server={}, cause={}", server, e.getMessage());
            return List.of();
        }
    }

    /**
     * 단계별(source/sink) 상태 귀속(#367, 상시 A RCA). 커넥터 watcher state로 단계 상태를 도출하고,
     * source delay·sink lag를 best-effort로 첨부한다. EDA는 SOURCE만, CDC는 SOURCE+SINK.
     */
    public PipelineStageStatusResponse stageStatus(UUID wsId, AuthenticatedUser principal, UUID id) {
        PipelineEntity p = loadPipeline(wsId, principal, id);
        Map<ConnectorKind, ConnectorEntity> byKind = new HashMap<>();
        for (ConnectorEntity c : connectorRepository.findByPipelineId(id)) {
            byKind.putIfAbsent(c.getKind(), c);
        }

        List<PipelineStageStatusResponse.StageStatus> stages = new ArrayList<>();
        stages.add(buildStage("SOURCE", byKind.get(ConnectorKind.SOURCE),
                latestSourceDelayMs(wsId, principal, id), null));
        if (p.getPattern() == PipelinePattern.DIRECT) {
            stages.add(buildStage("SINK", byKind.get(ConnectorKind.SINK),
                    null, currentSinkLag(wsId, principal, id)));
        }

        String bottleneck = firstStage(stages, "FAILED");
        if (bottleneck == null) bottleneck = firstStage(stages, "DEGRADED");

        return new PipelineStageStatusResponse(id, p.getStatus().name().toLowerCase(), stages, bottleneck);
    }

    private PipelineStageStatusResponse.StageStatus buildStage(String name, ConnectorEntity c,
                                                               Long delayMs, Long lagMessages) {
        String state = (c == null || c.getState() == null) ? "UNKNOWN" : c.getState();
        String status = switch (state) {
            case "FAILED" -> "FAILED";
            case "PARTIALLY_FAILED" -> "DEGRADED";
            case "PAUSED" -> "PAUSED";
            case "RUNNING" -> "OK";
            default -> "UNKNOWN";
        };
        String error = (c == null) ? null : c.getLastError();
        return new PipelineStageStatusResponse.StageStatus(name, state, status, error, delayMs, lagMessages);
    }

    /** source 소스 지연(ms) 최신값. Prometheus 미가용 시 null. */
    private Long latestSourceDelayMs(UUID wsId, AuthenticatedUser principal, UUID id) {
        List<MetricPoint> pts = sourceDelay(wsId, principal, id, 5);
        if (pts.isEmpty()) return null;
        double v = pts.get(pts.size() - 1).value();
        return v < 0 ? null : Math.round(v);   // -1 = idle/측정불가
    }

    /** sink consumer lag(메시지). Kafka/Prometheus 미가용 시 null. */
    private Long currentSinkLag(UUID wsId, AuthenticatedUser principal, UUID id) {
        try {
            return metrics(wsId, principal, id).lagMessages();
        } catch (Exception e) {
            return null;
        }
    }

    private static String firstStage(List<PipelineStageStatusResponse.StageStatus> stages, String status) {
        return stages.stream().filter(s -> status.equals(s.status()))
                .map(PipelineStageStatusResponse.StageStatus::stage).findFirst().orElse(null);
    }

    /** 미동기화 row 추이(#126, Sync 탭). consumer lag proxy. */
    public List<MetricPoint> unsynced(UUID wsId, AuthenticatedUser principal, UUID id, int minutes) {
        PipelineEntity p = loadPipeline(wsId, principal, id);
        String topic = p.getTopicName();
        if (topic == null || topic.isBlank() || !kafkaMetricsQuery.isEnabled()) return List.of();
        String group = sinkConsumerGroup(id);
        long[] win = window(minutes);
        try {
            return toPoints(kafkaMetricsQuery.unsyncedSeries(group, clampStart(win[0], p), win[1], win[2]));
        } catch (Exception e) {
            log.warn("미동기화 추이 조회 실패: group={}, cause={}", group, e.getMessage());
            return List.of();
        }
    }

    /**
     * 이 파이프라인 sink 커넥터의 Kafka Connect consumer group 이름.
     * Connect는 sink 커넥터마다 {@code connect-<커넥터명>} 그룹을 쓴다. 커넥터명은 결정적
     * {@code <pid>-sink}(#155)이며, 저장된 cr_name이 있으면 그것을 우선 사용한다.
     */
    private String sinkConsumerGroup(UUID pipelineId) {
        String sinkName = connectorRepository.findByPipelineId(pipelineId).stream()
                .filter(c -> c.getKind() == com.bifrost.ops.provisioning.dto.ConnectorKind.SINK)
                .map(ConnectorEntity::getCrName)
                .findFirst()
                .orElse(pipelineId + "-sink");
        return "connect-" + sinkName;
    }

    /** 이벤트 타입 분포 추이(#126, Sync 탭). Debezium create/update/delete 증가분. */
    public List<EventDistPoint> eventDistribution(UUID wsId, AuthenticatedUser principal, UUID id, int minutes) {
        PipelineEntity p = loadPipeline(wsId, principal, id);
        String server = debeziumServer(p);
        if (server == null || !kafkaMetricsQuery.isEnabled()) return List.of();
        // 버킷을 wall-clock의 step 배수에 고정하지 않으면 매 폴링마다 now 기준으로 창이 밀려
        // 같은 "HH:mm" 버킷이 다른 구간을 가리키고(과거 값이 계속 바뀜), step이 1분 미만이면 한 분에
        // 버킷이 둘이라 같은 라벨 막대가 2개 뜬다. step을 최소 60s(1분, 분당 막대 1개)로 두고
        // endSec을 step 경계로 내림해 과거 버킷을 고정한다. (카운터 차분엔 분당 4 scrape로 충분)
        long evStep   = Math.max(60L, stepFor(minutes));
        long endSec   = (System.currentTimeMillis() / 1000L / evStep) * evStep;
        long startSec = clampStart(endSec - Math.max(1, minutes) * 60L, p);
        try {
            // 첫 버킷도 차분 가능하도록 한 스텝 앞(startSec-evStep)부터 조회
            Map<Long, Double> ins = kafkaMetricsQuery.eventCountSeries(server, "create", startSec - evStep, endSec, evStep);
            Map<Long, Double> upd = kafkaMetricsQuery.eventCountSeries(server, "update", startSec - evStep, endSec, evStep);
            Map<Long, Double> del = kafkaMetricsQuery.eventCountSeries(server, "delete", startSec - evStep, endSec, evStep);
            java.util.TreeSet<Long> stamps = new java.util.TreeSet<>();
            stamps.addAll(ins.keySet());
            stamps.addAll(upd.keySet());
            stamps.addAll(del.keySet());
            List<EventDistPoint> out = new ArrayList<>(stamps.size());
            for (Long ts : stamps) {
                out.add(new EventDistPoint(
                        ts * 1000L,
                        Math.round(ins.getOrDefault(ts, 0.0)),
                        Math.round(upd.getOrDefault(ts, 0.0)),
                        Math.round(del.getOrDefault(ts, 0.0))));
            }
            return out;
        } catch (Exception e) {
            log.warn("이벤트 분포 조회 실패: server={}, cause={}", server, e.getMessage());
            return List.of();
        }
    }

    // ---- private ----

    /**
     * 메트릭 조회 시작 시각을 파이프라인 생성시각 이후로 제한(#404). 같은 datasource·테이블로 재생성하면
     * Debezium server 라벨(=토픽명)이 동일해 Prometheus에 남은 이전 파이프라인 시계열이 새 차트에 섞인다.
     * 생성 이전 데이터를 애초에 포함하지 않도록 startSec을 created_at으로 클램프한다.
     */
    private long clampStart(long startSec, PipelineEntity p) {
        if (p.getCreatedAt() == null) return startSec;
        return Math.max(startSec, p.getCreatedAt().getEpochSecond());
    }

    /** [startSec, endSec, stepSec] 윈도우. step은 창 길이에 비례(짧은 창일수록 촘촘). */
    private long[] window(int minutes) {
        long endSec = System.currentTimeMillis() / 1000L;
        long startSec = endSec - Math.max(1, minutes) * 60L;
        return new long[]{startSec, endSec, stepFor(minutes)};
    }

    /**
     * 창 길이에 맞춘 해상도(step, 초). 약 60점을 목표로 하되 최소 15초
     * (Prometheus scrape 간격보다 잘게 쪼개도 의미 없음). 예: 5m→15s, 15m→15s, 1h→60s, 3h→180s.
     */
    private static long stepFor(int minutes) {
        return Math.max(15L, minutes);
    }

    /** Map<ts초,값> → 시간순 MetricPoint(ms). */
    private List<MetricPoint> toPoints(Map<Long, Double> series) {
        List<MetricPoint> out = new ArrayList<>(series.size());
        new java.util.TreeMap<>(series).forEach((ts, v) -> out.add(new MetricPoint(ts * 1000L, v)));
        return out;
    }

    /**
     * Debezium server 이름 = topic.prefix. #365 이후 source의 topic.prefix를 최종 토픽명으로 두므로
     * (테이블 단위 유일한 server, RegexRouter로 중복 suffix 제거) server == 저장된 토픽명이다.
     */
    private String debeziumServer(PipelineEntity p) {
        String topic = p.getTopicName();
        return (topic == null || topic.isBlank()) ? null : topic;
    }

    private TopicInfoResponse fetchTopicInfo(String topic) throws Exception {
        // 1) describe topic → partitions, replicas, isr
        TopicDescription td = adminClient.describeTopics(List.of(topic))
                .allTopicNames().get(ADMIN_TIMEOUT_SEC, TimeUnit.SECONDS).get(topic);
        if (td == null) return new TopicInfoResponse(topic, 100.0, -1L, List.of());

        List<TopicPartitionInfo> partitions = td.partitions();

        // ISR 비율
        int totalReplicas = partitions.stream().mapToInt(p -> p.replicas().size()).sum();
        int totalIsr = partitions.stream().mapToInt(p -> p.isr().size()).sum();
        double isrPct = totalReplicas > 0 ? (double) totalIsr / totalReplicas * 100.0 : 100.0;

        // 2) begin/end offsets
        Map<TopicPartition, OffsetSpec> beginReqs = new HashMap<>();
        Map<TopicPartition, OffsetSpec> endReqs = new HashMap<>();
        for (TopicPartitionInfo tpi : partitions) {
            TopicPartition tp = new TopicPartition(topic, tpi.partition());
            beginReqs.put(tp, OffsetSpec.earliest());
            endReqs.put(tp, OffsetSpec.latest());
        }
        Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> begins =
                adminClient.listOffsets(beginReqs).all().get(ADMIN_TIMEOUT_SEC, TimeUnit.SECONDS);
        Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> ends =
                adminClient.listOffsets(endReqs).all().get(ADMIN_TIMEOUT_SEC, TimeUnit.SECONDS);

        // 3) retention config
        ConfigResource cr = new ConfigResource(ConfigResource.Type.TOPIC, topic);
        Config cfg = adminClient.describeConfigs(List.of(cr))
                .all().get(ADMIN_TIMEOUT_SEC, TimeUnit.SECONDS).get(cr);
        long retentionMs = -1L;
        if (cfg != null) {
            ConfigEntry entry = cfg.get("retention.ms");
            if (entry != null && entry.value() != null) {
                try { retentionMs = Long.parseLong(entry.value()); } catch (NumberFormatException ignored) {}
            }
        }

        List<TopicInfoResponse.PartitionDetail> details = partitions.stream().map(tpi -> {
            TopicPartition tp = new TopicPartition(topic, tpi.partition());
            String leader = tpi.leader() != null ? "broker-" + tpi.leader().id() : "unknown";
            long begin = begins.getOrDefault(tp, dummyOffset(0)).offset();
            long end = ends.getOrDefault(tp, dummyOffset(0)).offset();
            return new TopicInfoResponse.PartitionDetail(tpi.partition(), leader, begin, end);
        }).toList();

        return new TopicInfoResponse(topic, isrPct, retentionMs, details);
    }

    private List<ConsumerGroupInfo> fetchConsumerGroups(String topic) throws Exception {
        // 1) 전체 consumer group 목록
        List<String> allGroupIds = adminClient.listConsumerGroups()
                .all().get(ADMIN_TIMEOUT_SEC, TimeUnit.SECONDS)
                .stream().map(ConsumerGroupListing::groupId).toList();

        if (allGroupIds.isEmpty()) return List.of();

        // 2) 해당 토픽 구독 그룹 필터 (committed offsets로 판별)
        List<String> relevant = new ArrayList<>();
        for (String gid : allGroupIds) {
            try {
                Map<TopicPartition, OffsetAndMetadata> offsets = adminClient
                        .listConsumerGroupOffsets(gid)
                        .partitionsToOffsetAndMetadata()
                        .get(ADMIN_TIMEOUT_SEC, TimeUnit.SECONDS);
                if (offsets.keySet().stream().anyMatch(tp -> tp.topic().equals(topic))) {
                    relevant.add(gid);
                }
            } catch (Exception ignored) {}
        }
        if (relevant.isEmpty()) return List.of();

        // 3) describe groups → state, members
        Map<String, ConsumerGroupDescription> descs = adminClient
                .describeConsumerGroups(relevant)
                .all().get(ADMIN_TIMEOUT_SEC, TimeUnit.SECONDS);

        // 4) end offsets for lag
        List<TopicPartition> tps = descs.values().stream()
                .flatMap(d -> d.members().stream())
                .flatMap(m -> m.assignment().topicPartitions().stream())
                .filter(tp -> tp.topic().equals(topic))
                .distinct().toList();

        Map<TopicPartition, Long> endOffsets = new HashMap<>();
        if (!tps.isEmpty()) {
            Map<TopicPartition, OffsetSpec> endReqs = tps.stream()
                    .collect(Collectors.toMap(tp -> tp, tp -> OffsetSpec.latest()));
            adminClient.listOffsets(endReqs).all()
                    .get(ADMIN_TIMEOUT_SEC, TimeUnit.SECONDS)
                    .forEach((tp, info) -> endOffsets.put(tp, info.offset()));
        }

        // 5) 조합
        List<ConsumerGroupInfo> result = new ArrayList<>();
        for (String gid : relevant) {
            ConsumerGroupDescription desc = descs.get(gid);
            if (desc == null) continue;

            Map<TopicPartition, OffsetAndMetadata> committed = adminClient
                    .listConsumerGroupOffsets(gid)
                    .partitionsToOffsetAndMetadata()
                    .get(ADMIN_TIMEOUT_SEC, TimeUnit.SECONDS);

            List<ConsumerGroupInfo.PartitionOffset> partOffsets = new ArrayList<>();
            long totalLag = 0L;

            for (Map.Entry<TopicPartition, OffsetAndMetadata> e : committed.entrySet()) {
                if (!e.getKey().topic().equals(topic)) continue;
                long committedOffset = e.getValue().offset();
                long endOffset = endOffsets.getOrDefault(e.getKey(), committedOffset);
                long lag = Math.max(0, endOffset - committedOffset);
                totalLag += lag;

                String member = desc.members().stream()
                        .filter(m -> m.assignment().topicPartitions().contains(e.getKey()))
                        .map(MemberDescription::consumerId)
                        .findFirst().orElse(null);

                partOffsets.add(new ConsumerGroupInfo.PartitionOffset(
                        e.getKey().partition(), member, committedOffset, endOffset));
            }

            result.add(new ConsumerGroupInfo(
                    gid,
                    desc.state().toString(),
                    desc.members().size(),
                    totalLag,
                    -1L,
                    partOffsets));
        }
        return result;
    }

    private PipelineEntity loadPipeline(UUID wsId, AuthenticatedUser principal, UUID id) {
        accessGuard.requireAccess(wsId, principal);
        return pipelineRepository.findByIdAndTenantId(id, wsId)
                .orElseThrow(() -> new ApiException(ErrorCode.PIPELINE_NOT_FOUND,
                        "파이프라인을 찾을 수 없습니다"));
    }

    private static ListOffsetsResult.ListOffsetsResultInfo dummyOffset(long offset) {
        return new ListOffsetsResult.ListOffsetsResultInfo(offset, -1L, java.util.Optional.empty());
    }
}
