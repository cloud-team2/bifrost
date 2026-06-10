package com.bifrost.ops.pipeline.runtime;

import com.bifrost.ops.auth.jwt.AuthenticatedUser;
import com.bifrost.ops.global.common.error.ApiException;
import com.bifrost.ops.global.common.error.ErrorCode;
import com.bifrost.ops.pipeline.dto.ConnectionGuideResponse;
import com.bifrost.ops.pipeline.dto.ConnectionGuideResponse.AuthTemplate;
import com.bifrost.ops.pipeline.dto.ConnectionGuideResponse.SecretReference;
import com.bifrost.ops.pipeline.dto.ConnectionGuideResponse.TopicRef;
import com.bifrost.ops.pipeline.dto.TableMappingResponse;
import com.bifrost.ops.pipeline.dto.TableMappingResponse.TableMappingEntry;
import com.bifrost.ops.pipeline.persistence.entity.PipelineEntity;
import com.bifrost.ops.pipeline.persistence.repository.PipelineRepository;
import com.bifrost.ops.provisioning.naming.ConnectorNaming;
import com.bifrost.ops.provisioning.persistence.entity.ConnectorEntity;
import com.bifrost.ops.provisioning.persistence.repository.ConnectorRepository;
import com.bifrost.ops.workspace.WorkspaceAccessGuard;
import com.bifrost.ops.workspace.persistence.entity.WorkspaceEntity;
import com.bifrost.ops.workspace.persistence.repository.WorkspaceRepository;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Connection Guide와 Table Mapping API의 실 런타임 메타데이터 조회 서비스(#303).
 *
 * <p>Kubernetes Strimzi CR(Kafka/KafkaConnector)을 우선 읽고, KafkaConnector config가 CR에 없을 때만
 * Kafka Connect REST로 보강한다. connector config에는 DB password가 들어갈 수 있으므로 응답 DTO에는
 * config 원문을 절대 노출하지 않는다.
 */
@Service
public class PipelineRuntimeMetadataService {

    private static final Logger log = LoggerFactory.getLogger(PipelineRuntimeMetadataService.class);

    private final PipelineRepository pipelineRepository;
    private final WorkspaceRepository workspaceRepository;
    private final ConnectorRepository connectorRepository;
    private final WorkspaceAccessGuard accessGuard;
    private final KubernetesClient k8s;
    private final String kafkaNamespace;
    private final String kafkaClusterName;
    private final String fallbackBootstrapServers;
    private final String connectRestUrl;
    private final RestClient restClient;

    public PipelineRuntimeMetadataService(PipelineRepository pipelineRepository,
                                          WorkspaceRepository workspaceRepository,
                                          ConnectorRepository connectorRepository,
                                          WorkspaceAccessGuard accessGuard,
                                          KubernetesClient k8s,
                                          @Value("${kafka-cluster.namespace:platform-kafka}") String kafkaNamespace,
                                          @Value("${kafka-cluster.name:platform-kafka}") String kafkaClusterName,
                                          @Value("${spring.kafka.bootstrap-servers:localhost:9092}") String fallbackBootstrapServers,
                                          @Value("${kafka-connect.rest-url:http://platform-connect-connect-api.platform-kafka.svc:8083}") String connectRestUrl) {
        this.pipelineRepository = pipelineRepository;
        this.workspaceRepository = workspaceRepository;
        this.connectorRepository = connectorRepository;
        this.accessGuard = accessGuard;
        this.k8s = k8s;
        this.kafkaNamespace = kafkaNamespace;
        this.kafkaClusterName = kafkaClusterName;
        this.fallbackBootstrapServers = fallbackBootstrapServers;
        this.connectRestUrl = connectRestUrl;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000);
        factory.setReadTimeout(3000);
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    @Transactional(readOnly = true)
    public ConnectionGuideResponse connectionGuide(UUID wsId, AuthenticatedUser principal, UUID pipelineId) {
        PipelineEntity pipeline = loadPipeline(wsId, principal, pipelineId);
        WorkspaceEntity workspace = loadWorkspace(wsId);

        KafkaListenerInfo listener = kafkaListenerInfo();
        String secretName = ConnectorNaming.kafkaUserName(workspace.getNamespace());
        SecretReference secretRef = secretReference(secretName);
        List<TableMappingEntry> mappings = tableMappingsFromConnectors(pipeline);

        return new ConnectionGuideResponse(
                pipeline.getId(),
                pipeline.getName(),
                listener.bootstrapServers(),
                recommendedGroupId(workspace.getNamespace(), pipeline),
                listener.authenticationMethod(),
                secretRef,
                authenticationTemplates(listener, secretRef),
                topicRefs(pipeline, mappings));
    }

    @Transactional(readOnly = true)
    public TableMappingResponse tableMapping(UUID wsId, AuthenticatedUser principal, UUID pipelineId) {
        PipelineEntity pipeline = loadPipeline(wsId, principal, pipelineId);
        return new TableMappingResponse(
                pipeline.getId(),
                connectorName(pipeline, true),
                connectorName(pipeline, false),
                tableMappingsFromConnectors(pipeline));
    }

    private PipelineEntity loadPipeline(UUID wsId, AuthenticatedUser principal, UUID pipelineId) {
        accessGuard.requireAccess(wsId, principal);
        return pipelineRepository.findByIdAndTenantId(pipelineId, wsId)
                .orElseThrow(() -> new ApiException(ErrorCode.PIPELINE_NOT_FOUND, "파이프라인을 찾을 수 없습니다"));
    }

    private WorkspaceEntity loadWorkspace(UUID wsId) {
        return workspaceRepository.findById(wsId)
                .orElseThrow(() -> new ApiException(ErrorCode.WORKSPACE_NOT_FOUND, "워크스페이스를 찾을 수 없습니다"));
    }

    private String recommendedGroupId(String projectKey, PipelineEntity pipeline) {
        return "bifrost." + projectKey + "." + pipeline.getId();
    }

    private List<TopicRef> topicRefs(PipelineEntity pipeline, List<TableMappingEntry> mappings) {
        Map<String, TopicRef> out = new LinkedHashMap<>();
        if (notBlank(pipeline.getTopicName())) {
            out.put(pipeline.getTopicName(), new TopicRef(pipeline.getTopicName(), sourceTable(pipeline), "pipeline"));
        }
        for (TableMappingEntry mapping : mappings) {
            if (notBlank(mapping.kafkaTopic())) {
                out.putIfAbsent(mapping.kafkaTopic(),
                        new TopicRef(mapping.kafkaTopic(), mapping.sourceTable(), "connector"));
            }
        }
        return List.copyOf(out.values());
    }

    private String sourceTable(PipelineEntity pipeline) {
        if (notBlank(pipeline.getSchemaName()) && notBlank(pipeline.getTableName())) {
            return pipeline.getSchemaName() + "." + pipeline.getTableName();
        }
        return null;
    }

    private SecretReference secretReference(String secretName) {
        List<String> keys = secretKeys(secretName);
        Map<String, String> refs = new LinkedHashMap<>();
        refs.put("username", firstPresent(keys, "sasl.username", "username"));
        refs.put("password", firstPresent(keys, "sasl.password", "password"));
        refs.put("jaasConfig", firstPresent(keys, "sasl.jaas.config"));
        refs.put("caCert", firstPresent(keys, "ca.crt"));
        refs.put("userCert", firstPresent(keys, "user.crt"));
        refs.put("userKey", firstPresent(keys, "user.key"));
        refs.entrySet().removeIf(e -> e.getValue() == null);
        return new SecretReference(kafkaNamespace, secretName, refs, keys);
    }

    private List<String> secretKeys(String secretName) {
        try {
            Secret secret = k8s.secrets().inNamespace(kafkaNamespace).withName(secretName).get();
            if (secret == null || secret.getData() == null) {
                return List.of();
            }
            List<String> keys = new ArrayList<>(secret.getData().keySet());
            Collections.sort(keys);
            return keys;
        } catch (RuntimeException e) {
            log.warn("KafkaUser Secret key 목록 조회 실패(값 미노출): secret={}, cause={}",
                    secretName, e.getClass().getSimpleName());
            return List.of();
        }
    }

    private List<AuthTemplate> authenticationTemplates(KafkaListenerInfo listener, SecretReference secretRef) {
        // listener의 실제 auth 방식에 맞는 템플릿만 반환한다 — 둘 다 무조건 반환하면 client auth 방식을 오도한다.
        String method = listener.authenticationMethod();
        List<AuthTemplate> out = new ArrayList<>();

        if ("SCRAM-SHA-512".equalsIgnoreCase(method)) {
            Map<String, String> scram = new LinkedHashMap<>();
            scram.put("security.protocol", listener.tls() ? "SASL_SSL" : "SASL_PLAINTEXT");
            scram.put("sasl.mechanism", "SCRAM-SHA-512");
            scram.put("sasl.jaas.config",
                    "org.apache.kafka.common.security.scram.ScramLoginModule required username=\"${username}\" password=\"${password}\";");
            out.add(new AuthTemplate("SCRAM-SHA-512", scram.get("security.protocol"), scram, secretRef));
        } else if ("mTLS".equalsIgnoreCase(method)) {
            Map<String, String> mtls = new LinkedHashMap<>();
            mtls.put("security.protocol", "SSL");
            mtls.put("ssl.truststore.location", "${truststorePath}");
            mtls.put("ssl.truststore.password", "${truststorePassword}");
            mtls.put("ssl.keystore.location", "${keystorePath}");
            mtls.put("ssl.keystore.password", "${keystorePassword}");
            out.add(new AuthTemplate("mTLS", "SSL", mtls, secretRef));
        }
        // method가 none/unknown이면 client 자격증명 템플릿 없음(빈 리스트).
        return List.copyOf(out);
    }

    @SuppressWarnings("unchecked")
    private KafkaListenerInfo kafkaListenerInfo() {
        try {
            GenericKubernetesResource cr = k8s.genericKubernetesResources("kafka.strimzi.io/v1", "Kafka")
                    .inNamespace(kafkaNamespace).withName(kafkaClusterName).get();
            if (cr == null) {
                return fallbackListener();
            }
            Map<String, Object> spec = asMap(cr.getAdditionalProperties().get("spec"));
            Map<String, Object> kafka = asMap(spec.get("kafka"));
            List<Map<String, Object>> listeners = asMapList(kafka.get("listeners"));
            Map<String, Object> chosen = chooseListener(listeners).orElse(Map.of());
            String name = string(chosen.get("name"), "plain");
            int port = number(chosen.get("port"), fallbackPort());
            boolean tls = bool(chosen.get("tls"));
            Map<String, Object> auth = asMap(chosen.get("authentication"));
            // authentication 블록의 type만 client auth로 간주한다. tls=true는 transport 암호화일 뿐
            // client auth(mTLS)가 아니므로, authentication이 없으면 "none"(인증 미적용)으로 분류한다.
            String authType = string(auth.get("type"), "none");
            String bootstrap = bootstrapFromStatus(cr, name)
                    .orElse(kafkaClusterName + "-kafka-bootstrap." + kafkaNamespace + ".svc:" + port);
            return new KafkaListenerInfo(bootstrap, normalizeAuth(authType), tls);
        } catch (RuntimeException e) {
            log.warn("Kafka CR 조회 실패, spring.kafka.bootstrap-servers로 폴백: cause={}",
                    e.getClass().getSimpleName());
            return fallbackListener();
        }
    }

    private Optional<Map<String, Object>> chooseListener(List<Map<String, Object>> listeners) {
        Optional<Map<String, Object>> scram = listeners.stream()
                .filter(l -> "scram-sha-512".equalsIgnoreCase(string(asMap(l.get("authentication")).get("type"), "")))
                .findFirst();
        if (scram.isPresent()) {
            return scram;
        }
        Optional<Map<String, Object>> mtls = listeners.stream()
                .filter(l -> "tls".equalsIgnoreCase(string(asMap(l.get("authentication")).get("type"), "")))
                .findFirst();
        return mtls.isPresent() ? mtls : listeners.stream().findFirst();
    }

    private Optional<String> bootstrapFromStatus(GenericKubernetesResource cr, String listenerName) {
        Map<String, Object> status = asMap(cr.getAdditionalProperties().get("status"));
        for (Map<String, Object> listener : asMapList(status.get("listeners"))) {
            if (listenerName.equals(string(listener.get("name"), null)) && listener.get("bootstrapServers") != null) {
                return Optional.of(listener.get("bootstrapServers").toString());
            }
        }
        return Optional.empty();
    }

    private KafkaListenerInfo fallbackListener() {
        // CR 조회 실패 fallback: client auth는 알 수 없음 → NONE (PLAINTEXT로 적으면 client auth 미적용을 평문과 혼동).
        return new KafkaListenerInfo(fallbackBootstrapServers, "NONE", false);
    }

    private int fallbackPort() {
        String[] parts = fallbackBootstrapServers.split(":");
        if (parts.length > 1) {
            try {
                return Integer.parseInt(parts[parts.length - 1]);
            } catch (NumberFormatException ignored) {
                return 9092;
            }
        }
        return 9092;
    }

    private String normalizeAuth(String authType) {
        // client 인증 방식만 표현한다(NONE/SCRAM/mTLS). transport TLS 여부는 KafkaListenerInfo.tls로 별도 표현 —
        // 인증 없음을 PLAINTEXT로 적으면 "TLS transport + no client auth"를 평문으로 오도한다.
        return switch (authType.toLowerCase(Locale.ROOT)) {
            case "scram-sha-512" -> "SCRAM-SHA-512";
            case "tls" -> "mTLS";
            default -> "NONE";
        };
    }

    private List<TableMappingEntry> tableMappingsFromConnectors(PipelineEntity pipeline) {
        String sourceName = connectorName(pipeline, true);
        Map<String, String> source = connectorConfig(sourceName);
        if (source.isEmpty()) {
            return List.of();
        }
        Map<String, String> sink = connectorConfig(connectorName(pipeline, false));

        List<String> tables = splitCsv(source.get("table.include.list"));
        if (tables.isEmpty()) {
            return List.of();
        }
        List<String> sinkTopics = splitCsv(sink.get("topics"));
        String topicPrefix = source.get("topic.prefix");
        List<TableMappingEntry> out = new ArrayList<>();
        for (int i = 0; i < tables.size(); i++) {
            String sourceTable = tables.get(i);
            String topic = topicFor(sourceTable, topicPrefix, sinkTopics, i);
            out.add(new TableMappingEntry(sourceTable, topic, sinkTable(sink, topic, sourceTable)));
        }
        return List.copyOf(out);
    }

    private String topicFor(String sourceTable, String topicPrefix, List<String> sinkTopics, int index) {
        if (index < sinkTopics.size() && notBlank(sinkTopics.get(index))) {
            return sinkTopics.get(index);
        }
        if (notBlank(topicPrefix)) {
            return topicPrefix + "." + sourceTable;
        }
        return null;
    }

    private String sinkTable(Map<String, String> sinkConfig, String topic, String sourceTable) {
        if (sinkConfig.isEmpty()) {
            return null;
        }
        String explicit = sinkConfig.get("table.name.format");
        if (notBlank(explicit)) {
            return explicit.replace("${topic}", topic != null ? topic : "");
        }
        String transforms = sinkConfig.get("transforms");
        if (transforms != null && transforms.contains("route")
                && "$1".equals(sinkConfig.get("transforms.route.replacement"))) {
            return lastSegment(topic != null ? topic : sourceTable);
        }
        return lastSegment(sourceTable);
    }

    private String connectorName(PipelineEntity pipeline, boolean source) {
        String configured = source ? pipeline.getSourceConnectorName() : pipeline.getSinkConnectorName();
        if (notBlank(configured)) {
            return configured;
        }
        if (source) {
            return ConnectorNaming.sourceConnectorName(pipeline.getId());
        }
        List<ConnectorEntity> rows = connectorRepository.findByPipelineId(pipeline.getId());
        if (rows == null) {
            rows = List.of();
        }
        return rows.stream()
                .filter(c -> c.getKind() != null && "SINK".equals(c.getKind().name()))
                .map(ConnectorEntity::getCrName)
                .filter(PipelineRuntimeMetadataService::notBlank)
                .findFirst()
                .orElse(ConnectorNaming.sinkConnectorName(pipeline.getId()));
    }

    private Map<String, String> connectorConfig(String connectorName) {
        if (!notBlank(connectorName)) {
            return Map.of();
        }
        Map<String, String> fromCr = connectorConfigFromKubernetes(connectorName);
        if (!fromCr.isEmpty()) {
            return fromCr;
        }
        return connectorConfigFromRest(connectorName);
    }

    private Map<String, String> connectorConfigFromKubernetes(String connectorName) {
        try {
            GenericKubernetesResource cr = k8s.genericKubernetesResources("kafka.strimzi.io/v1", "KafkaConnector")
                    .inNamespace(kafkaNamespace).withName(connectorName).get();
            if (cr == null) {
                return Map.of();
            }
            Map<String, Object> spec = asMap(cr.getAdditionalProperties().get("spec"));
            return stringifyMap(asMap(spec.get("config")));
        } catch (RuntimeException e) {
            log.warn("KafkaConnector CR config 조회 실패: name={}, cause={}",
                    connectorName, e.getClass().getSimpleName());
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> connectorConfigFromRest(String connectorName) {
        try {
            String encoded = URLEncoder.encode(connectorName, StandardCharsets.UTF_8);
            Map<String, Object> raw = restClient.get()
                    .uri(connectRestUrl + "/connectors/" + encoded + "/config")
                    .retrieve()
                    .body(Map.class);
            return stringifyMap(raw == null ? Map.of() : raw);
        } catch (RuntimeException e) {
            log.warn("Kafka Connect REST config 조회 실패: name={}, cause={}",
                    connectorName, e.getClass().getSimpleName());
            return Map.of();
        }
    }

    private static Map<String, String> stringifyMap(Map<?, ?> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        raw.forEach((k, v) -> {
            if (k != null && v != null) {
                out.put(String.valueOf(k), String.valueOf(v));
            }
        });
        return out;
    }

    private static List<String> splitCsv(String raw) {
        if (!notBlank(raw)) {
            return List.of();
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return List.copyOf(out);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> {
                if (k != null) {
                    out.put(String.valueOf(k), v);
                }
            });
            return out;
        }
        return Map.of();
    }

    private static List<Map<String, Object>> asMapList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .map(PipelineRuntimeMetadataService::asMap)
                .filter(m -> !m.isEmpty())
                .toList();
    }

    private static String string(Object value, String fallback) {
        return value == null ? fallback : value.toString();
    }

    private static int number(Object value, int fallback) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(value.toString());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static boolean bool(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        return value != null && Boolean.parseBoolean(value.toString());
    }

    private static String firstPresent(List<String> availableKeys, String... candidates) {
        for (String candidate : candidates) {
            if (availableKeys.contains(candidate)) {
                return candidate;
            }
        }
        // Secret에 실제로 존재하는 key만 참조한다 — 없으면 null(없는 key를 가리켜 준비상태를 오도하지 않음).
        return null;
    }

    private static String lastSegment(String value) {
        if (!notBlank(value)) {
            return null;
        }
        int dot = value.lastIndexOf('.');
        return dot >= 0 ? value.substring(dot + 1) : value;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private record KafkaListenerInfo(String bootstrapServers, String authenticationMethod, boolean tls) {
    }
}
