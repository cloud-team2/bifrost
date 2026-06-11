package com.bifrost.ops.cluster;

import com.bifrost.ops.adapters.prometheus.PrometheusClient;
import com.bifrost.ops.cluster.dto.ConnectClusterResponse;
import com.bifrost.ops.cluster.dto.ConnectClusterResponse.ConnectorRow;
import com.bifrost.ops.cluster.dto.ConnectClusterResponse.Plugin;
import com.bifrost.ops.cluster.dto.ConnectClusterResponse.Worker;
import com.bifrost.ops.cluster.dto.KafkaClusterResponse;
import com.bifrost.ops.cluster.dto.KafkaClusterResponse.BrokerInfo;
import com.bifrost.ops.monitoring.query.KafkaMetricsQuery;
import com.bifrost.ops.pipeline.dto.ThroughputPoint;
import com.bifrost.ops.pipeline.persistence.entity.PipelineEntity;
import com.bifrost.ops.pipeline.persistence.repository.PipelineRepository;
import com.bifrost.ops.provisioning.persistence.entity.ConnectorEntity;
import com.bifrost.ops.provisioning.persistence.repository.ConnectorRepository;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.LogDirDescription;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartitionInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Kafka 클러스터 메트릭 조회(Cluster 화면, #213).
 *
 * <p>구조·파티션 건강은 <b>AdminClient</b>(JMX 의존 없이 정확), per-broker 리소스(cpu/disk/net)와
 * 처리량 시계열은 <b>Prometheus</b>(node-exporter/cAdvisor/JMX exporter)에서 읽는다. Prometheus가
 * 비활성·실패해도 구조 데이터는 반환하고 리소스 필드만 null로 둔다(graceful degrade).
 */
@Service
public class ClusterService {

    private static final Logger log = LoggerFactory.getLogger(ClusterService.class);
    private static final long ADMIN_TIMEOUT_SEC = 5L;

    private final AdminClient admin;
    private final PrometheusClient prometheus;
    private final boolean promEnabled;
    private final String namespace;
    private final KubernetesClient k8s;
    private final ConnectorRepository connectorRepository;
    private final PipelineRepository pipelineRepository;
    private final String connectCluster;
    private final String connectRestUrl;

    public ClusterService(AdminClient admin,
                          PrometheusClient prometheus,
                          KafkaMetricsQuery kafkaMetricsQuery,
                          KubernetesClient k8s,
                          ConnectorRepository connectorRepository,
                          PipelineRepository pipelineRepository,
                          @Value("${kafka-cluster.namespace:platform-kafka}") String namespace,
                          @Value("${kafka-connect.cluster:platform-connect}") String connectCluster,
                          @Value("${kafka-connect.rest-url:http://platform-connect-connect-api.platform-kafka.svc:8083}") String connectRestUrl) {
        this.admin = admin;
        this.prometheus = prometheus;
        this.promEnabled = kafkaMetricsQuery.isEnabled();
        this.k8s = k8s;
        this.connectorRepository = connectorRepository;
        this.pipelineRepository = pipelineRepository;
        this.namespace = namespace;
        this.connectCluster = connectCluster;
        this.connectRestUrl = connectRestUrl;
    }

    /** Brokers 탭: 컨트롤러·파티션 건강·브로커별 리소스. */
    public KafkaClusterResponse kafkaCluster() {
        try {
            String status = "healthy";
            String message = null;

            var dc = admin.describeCluster();
            Collection<Node> nodes = dc.nodes().get(ADMIN_TIMEOUT_SEC, TimeUnit.SECONDS);
            Node controller = null;
            try {
                controller = dc.controller().get(ADMIN_TIMEOUT_SEC, TimeUnit.SECONDS);
            } catch (Exception e) {
                restoreInterrupt(e);
                status = "warning";
                message = appendMessage(message, "controller unavailable");
                log.warn("Kafka 컨트롤러 조회 실패, controllerId=-1로 폴백: {}", e.getMessage());
            }
            int controllerId = controller != null ? controller.id() : -1;

            // 토픽 → 파티션 건강 + 브로커별 leader 수
            long total = 0, underRep = 0, offline = 0;
            Map<Integer, Long> leaderCount = new HashMap<>();
            try {
                Set<String> topics = admin.listTopics().names().get(ADMIN_TIMEOUT_SEC, TimeUnit.SECONDS);
                Map<String, TopicDescription> descs = topics.isEmpty()
                        ? Map.of()
                        : admin.describeTopics(topics).allTopicNames().get(ADMIN_TIMEOUT_SEC, TimeUnit.SECONDS);
                for (TopicDescription td : descs.values()) {
                    for (TopicPartitionInfo p : td.partitions()) {
                        total++;
                        if (p.leader() == null || p.leader().id() < 0) {
                            offline++;
                        } else {
                            leaderCount.merge(p.leader().id(), 1L, Long::sum);
                        }
                        if (p.isr().size() < p.replicas().size()) underRep++;
                    }
                }
            } catch (Exception e) {
                restoreInterrupt(e);
                status = "warning";
                message = appendMessage(message, "topic metadata unavailable");
                log.warn("Kafka 토픽 조회 실패, 브로커 기본 정보만 반환: {}", e.getMessage());
            }

            // 브로커별 로그 디렉토리 바이트 + 데이터 디스크 사용률
            List<Integer> brokerIds = nodes.stream().map(Node::id).collect(Collectors.toList());
            BrokerStorage storage = brokerStorage(brokerIds);
            if (storage.degraded()) {
                status = "warning";
                message = appendMessage(message, "log dirs unavailable");
            }

            List<BrokerInfo> brokers = new ArrayList<>();
            for (Node n : nodes) {
                brokers.add(brokerInfo(n, controllerId, leaderCount.getOrDefault(n.id(), 0L),
                        storage.bytes().getOrDefault(n.id(), 0L), storage.diskPct().get(n.id())));
            }
            brokers.sort(java.util.Comparator.comparingInt(BrokerInfo::id));

            return new KafkaClusterResponse(controllerId, nodes.size(), total, underRep, offline,
                    brokers, status, message);
        } catch (Exception e) {
            restoreInterrupt(e);
            log.warn("Kafka 클러스터 조회 실패: {}", e.getMessage());
            return new KafkaClusterResponse(-1, 0, 0, 0, 0, List.of(),
                    "error", "cluster metadata unavailable");
        }
    }

    private BrokerStorage brokerStorage(List<Integer> brokerIds) {
        try {
            Map<Integer, Map<String, LogDirDescription>> dirs =
                    admin.describeLogDirs(brokerIds).allDescriptions().get(ADMIN_TIMEOUT_SEC, TimeUnit.SECONDS);
            Map<Integer, Long> bytes = new HashMap<>();
            Map<Integer, Double> diskPct = new HashMap<>();
            dirs.forEach((broker, perDir) -> {
                long sum = perDir.values().stream()
                        .flatMap(d -> d.replicaInfos().values().stream())
                        .mapToLong(r -> r.size())
                        .sum();
                bytes.put(broker, sum);
                // 데이터 디렉토리 디스크 사용률(KIP-827, kafka-clients 3.3+). 브로커가 값을 안 주면(Optional empty)
                // null로 두고 brokerInfo에서 node_filesystem으로 폴백한다. node_filesystem 전역 평균보다 정확.
                long total = 0, used = 0;
                boolean hasCap = false;
                for (LogDirDescription d : perDir.values()) {
                    if (d.totalBytes().isPresent() && d.usableBytes().isPresent()) {
                        total += d.totalBytes().getAsLong();
                        used += d.totalBytes().getAsLong() - d.usableBytes().getAsLong();
                        hasCap = true;
                    }
                }
                if (hasCap && total > 0) diskPct.put(broker, (double) used / total * 100.0);
            });
            return new BrokerStorage(bytes, diskPct, false);
        } catch (Exception e) {
            restoreInterrupt(e);
            log.warn("logDirs 조회 실패: {}", e.getMessage());
            return new BrokerStorage(Map.of(), Map.of(), true);
        }
    }

    private static String appendMessage(String current, String next) {
        return current == null ? next : current + "; " + next;
    }

    private static void restoreInterrupt(Exception e) {
        if (e instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
    }

    private record BrokerStorage(Map<Integer, Long> bytes, Map<Integer, Double> diskPct, boolean degraded) {
    }

    private BrokerInfo brokerInfo(Node n, int controllerId, long leaderPartitions,
                                  long logDirBytes, Double diskFromLogDirs) {
        // 브로커 JMX/JVM exporter는 instance=pod 호스트로 라벨된다(예: platform-kafka-kafka-0.{cluster}-kafka-brokers...).
        // [.]로 id 뒤 점을 고정 매칭해 kafka-1이 kafka-10/11을 잡지 않게 한다. (cAdvisor는 pod 라벨이 없어 0 나옴)
        String inst = "platform-kafka-kafka-" + n.id() + "[.].*";
        // CPU: JVM process CPU(%). container CPU(cAdvisor)는 라벨 미스로 0이라 process_cpu로 교정.
        Double cpu = scalarOrNull(
                "rate(process_cpu_seconds_total{job=\"kafka-broker\",instance=~\"" + inst + "\"}[2m]) * 100");
        // Memory: 브로커 JVM heap used/max(JMX exporter, area=heap). Kafka heap 압박을 보여주는 표준 지표.
        Double heapUsed = scalarOrNull(
                "sum(jvm_memory_used_bytes{job=\"kafka-broker\",area=\"heap\",instance=~\"" + inst + "\"})");
        Double heapMax = scalarOrNull(
                "sum(jvm_memory_max_bytes{job=\"kafka-broker\",area=\"heap\",instance=~\"" + inst + "\"})");
        // Net: 컨테이너 네트워크가 아니라 Kafka 브로커 처리량(BytesIn/OutPerSec) — 모든 토픽 합.
        Double netIn = scalarOrNull(
                "sum(rate(kafka_server_brokertopicmetrics_bytesin_total{instance=~\"" + inst + "\"}[2m]))");
        Double netOut = scalarOrNull(
                "sum(rate(kafka_server_brokertopicmetrics_bytesout_total{instance=~\"" + inst + "\"}[2m]))");
        // Disk: describeLogDirs의 데이터 볼륨 사용률(per-broker). 미제공이면 node_filesystem(노드 전역)으로 폴백.
        Double disk = diskFromLogDirs != null ? diskFromLogDirs : scalarOrNull(
                "(1 - sum(node_filesystem_avail_bytes{fstype!~\"tmpfs|overlay|squashfs\"}) "
                        + "/ sum(node_filesystem_size_bytes{fstype!~\"tmpfs|overlay|squashfs\"})) * 100");
        Double memPct = (heapUsed != null && heapMax != null && heapMax > 0) ? heapUsed / heapMax * 100 : null;
        String status = (cpu != null && cpu > 90) || (disk != null && disk > 85)
                || (memPct != null && memPct > 90) ? "warning" : "healthy";
        return new BrokerInfo(n.id(), n.host(), n.port(), n.id() == controllerId,
                leaderPartitions, logDirBytes, round(cpu), toLong(heapUsed), toLong(heapMax),
                round(disk), netIn, netOut, status);
    }

    /** 클러스터 처리량 추이(produce/consume msg/s) — broker JMX messagesin/out. */
    public List<ThroughputPoint> throughput(int minutes) {
        if (!promEnabled) return List.of();
        long end = System.currentTimeMillis() / 1000L;
        long start = end - Math.max(1, minutes) * 60L;
        long step = Math.max(15L, minutes);
        try {
            Map<Long, Double> in = prometheus.queryRange(
                    "sum(rate(kafka_server_brokertopicmetrics_messagesin_total[1m]))", start, end, step);
            Map<Long, Double> out = prometheus.queryRange(
                    "sum(rate(kafka_server_brokertopicmetrics_messagesout_total[1m]))", start, end, step);
            TreeSet<Long> ts = new TreeSet<>();
            ts.addAll(in.keySet());
            ts.addAll(out.keySet());
            List<ThroughputPoint> series = new ArrayList<>(ts.size());
            for (Long t : ts) {
                series.add(new ThroughputPoint(t * 1000L, in.getOrDefault(t, 0.0), out.getOrDefault(t, 0.0)));
            }
            return series;
        } catch (Exception e) {
            log.warn("클러스터 처리량 조회 실패: {}", e.getMessage());
            return List.of();
        }
    }

    /** KafkaConnect 탭: worker 파드·connectors·plugins·config. */
    public ConnectClusterResponse connect() {
        return new ConnectClusterResponse(workers(), connectors(), plugins(), connectConfig());
    }

    private List<Worker> workers() {
        try {
            List<Pod> pods = k8s.pods().inNamespace(namespace)
                    .withLabel("strimzi.io/cluster", connectCluster).list().getItems();
            // Service 기반 스크랩이라 per-pod 라벨이 없다 → connect job 집계값을 worker에 귀속(단일 파드 기준).
            Double heapUsed = scalarOrNull("sum(jvm_memory_used_bytes{area=\"heap\",job=\"kafka-connect\"})");
            Double heapMax = scalarOrNull("sum(jvm_memory_max_bytes{area=\"heap\",job=\"kafka-connect\"})");
            Double cpu = round(scalarOrNull("rate(process_cpu_seconds_total{job=\"kafka-connect\"}[2m]) * 100"));
            Double gc = round(scalarOrNull("sum(jvm_gc_collection_seconds_sum{job=\"kafka-connect\"})"));
            // worker 버전 = KafkaConnect CR spec.version(=Kafka 버전). 컨테이너 이미지 태그(예: 'v1')가 아님.
            String version = connectVersion();
            List<Worker> out = new ArrayList<>();
            for (Pod p : pods) {
                String state = p.getStatus() != null ? p.getStatus().getPhase() : "Unknown";
                String ip = p.getStatus() != null ? p.getStatus().getPodIP() : null;
                out.add(new Worker(p.getMetadata().getName(), ip, state,
                        toLong(heapUsed), toLong(heapMax), cpu, gc, version));
            }
            return out;
        } catch (Exception e) {
            log.warn("connect worker 조회 실패: {}", e.getMessage());
            return List.of();
        }
    }

    private List<ConnectorRow> connectors() {
        try {
            List<ConnectorEntity> cs = connectorRepository.findAll();
            // 파이프라인명 매핑(N+1 방지용 캐시)
            Map<UUID, String> pipelineNames = new HashMap<>();
            List<ConnectorRow> out = new ArrayList<>(cs.size());
            for (ConnectorEntity c : cs) {
                String pipeline = pipelineNames.computeIfAbsent(c.getPipelineId(), id ->
                        pipelineRepository.findById(id).map(PipelineEntity::getName).orElse("-"));
                out.add(new ConnectorRow(c.getCrName(),
                        c.getKind() != null ? c.getKind().name() : "-",
                        c.getState() != null ? c.getState() : "UNKNOWN",
                        pipeline, c.getTasksMax()));
            }
            return out;
        } catch (Exception e) {
            log.warn("connectors 조회 실패: {}", e.getMessage());
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private List<Plugin> plugins() {
        try {
            // Connect REST: 설치된 커넥터 플러그인 카탈로그. best-effort라 짧은 타임아웃으로 빠르게 실패.
            var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(2000);
            factory.setReadTimeout(3000);
            List<Map<String, Object>> raw = RestClient.builder().requestFactory(factory).build().get()
                    .uri(connectRestUrl + "/connector-plugins")
                    .retrieve().body(List.class);
            if (raw != null && !raw.isEmpty()) {
                List<Plugin> out = new ArrayList<>();
                for (Map<String, Object> m : raw) {
                    out.add(new Plugin(
                            String.valueOf(m.get("class")),
                            String.valueOf(m.getOrDefault("type", "-")),
                            String.valueOf(m.getOrDefault("version", "-"))));
                }
                return out;
            }
        } catch (Exception e) {
            log.warn("connector-plugins 조회 실패, 사용 중 클래스로 폴백: {}", e.getMessage());
        }
        return pluginsFromConnectors();
    }

    /** Connect REST 미응답 시 폴백: 실제 사용 중인 커넥터 클래스 목록(version 미상). */
    private List<Plugin> pluginsFromConnectors() {
        try {
            Map<String, String> classToType = new java.util.LinkedHashMap<>();
            for (ConnectorEntity c : connectorRepository.findAll()) {
                if (c.getConnectorClass() != null) {
                    classToType.putIfAbsent(c.getConnectorClass(),
                            c.getKind() != null && c.getKind().name().equals("SINK") ? "sink" : "source");
                }
            }
            return classToType.entrySet().stream()
                    .map(e -> new Plugin(e.getKey(), e.getValue(), "-"))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> connectConfig() {
        try {
            GenericKubernetesResource cr = k8s.genericKubernetesResources("kafka.strimzi.io/v1", "KafkaConnect")
                    .inNamespace(namespace).withName(connectCluster).get();
            if (cr == null) return Map.of();
            Map<String, Object> spec = (Map<String, Object>) cr.getAdditionalProperties().get("spec");
            if (spec == null) return Map.of();
            Map<String, String> out = new java.util.LinkedHashMap<>();
            for (String k : List.of("groupId", "configStorageTopic", "offsetStorageTopic", "statusStorageTopic")) {
                if (spec.get(k) != null) out.put(k, String.valueOf(spec.get(k)));
            }
            Object cfg = spec.get("config");
            if (cfg instanceof Map<?, ?> m) {
                m.forEach((k, v) -> out.put(String.valueOf(k), String.valueOf(v)));
            }
            return out;
        } catch (Exception e) {
            log.warn("connect config 조회 실패: {}", e.getMessage());
            return Map.of();
        }
    }

    /** KafkaConnect CR의 spec.version(= 구동 중인 Kafka/Connect 버전). 없으면 null. */
    @SuppressWarnings("unchecked")
    private String connectVersion() {
        try {
            GenericKubernetesResource cr = k8s.genericKubernetesResources("kafka.strimzi.io/v1", "KafkaConnect")
                    .inNamespace(namespace).withName(connectCluster).get();
            if (cr == null) return null;
            Object spec = cr.getAdditionalProperties().get("spec");
            if (spec instanceof Map<?, ?> m && m.get("version") != null) {
                return String.valueOf(m.get("version"));
            }
            return null;
        } catch (Exception e) {
            log.warn("connect version 조회 실패: {}", e.getMessage());
            return null;
        }
    }

    private static Long toLong(Double d) {
        return d == null ? null : d.longValue();
    }

    private Double scalarOrNull(String promql) {
        if (!promEnabled) return null;
        try {
            double v = prometheus.queryScalar(promql);
            return Double.isNaN(v) ? null : v;
        } catch (Exception e) {
            return null;
        }
    }

    private static Double round(Double v) {
        return v == null ? null : Math.round(v * 10.0) / 10.0;
    }
}
