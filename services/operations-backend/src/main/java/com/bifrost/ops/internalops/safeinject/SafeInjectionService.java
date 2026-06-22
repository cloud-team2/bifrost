package com.bifrost.ops.internalops.safeinject;

import com.bifrost.ops.global.common.error.ApiException;
import com.bifrost.ops.global.common.error.ErrorCode;
import com.bifrost.ops.workspace.persistence.entity.WorkspaceEntity;
import com.bifrost.ops.workspace.persistence.repository.WorkspaceRepository;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.GenericKubernetesResourceBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class SafeInjectionService {

    public static final String LABEL_SAFE = "bifrost.io/safe-inject";
    public static final String LABEL_RUN = "bifrost.io/safe-inject-run";
    public static final String LABEL_FAULT = "bifrost.io/safe-inject-fault";
    public static final String LABEL_PROJECT = "bifrost.io/safe-inject-project";
    public static final String LABEL_CLUSTER = "strimzi.io/cluster";

    private static final String OP_SCOPE = "safeinject:test-connector-only";
    private static final String CONNECTOR_PREFIX = "safeinject-";
    private static final Pattern DNS_LABEL = Pattern.compile("[a-z0-9]([-a-z0-9]*[a-z0-9])?");
    private static final Pattern RUN_ID = Pattern.compile("[A-Za-z0-9._:-]{1,80}");

    private final KubernetesClient k8s;
    private final WorkspaceRepository workspaceRepository;
    private final String namespace;
    private final String connectCluster;
    private final Set<String> allowedProjects;

    public SafeInjectionService(KubernetesClient k8s,
                                WorkspaceRepository workspaceRepository,
                                @Value("${kafka-cluster.namespace:platform-kafka}") String namespace,
                                @Value("${kafka-connect.cluster:platform-connect}") String connectCluster,
                                @Value("${safe-inject.allowed-projects:safeinject,e2e-rca-test,e2e-rca-test-0621}")
                                String allowedProjects) {
        this.k8s = k8s;
        this.workspaceRepository = workspaceRepository;
        this.namespace = nonBlankOrDefault(namespace, "platform-kafka");
        this.connectCluster = nonBlankOrDefault(connectCluster, "platform-connect");
        this.allowedProjects = parseAllowedProjects(allowedProjects);
    }

    public SafeInjectionCreateResult create(String projectId, SafeInjectionCreateRequest request) {
        SafeInjectionFault fault = SafeInjectionFault.parse(request.fault());
        String runId = requireRunId(request.runId());
        WorkspaceEntity workspace = requireWorkspace(projectId);
        requireTestWorkspace(projectId, workspace);

        String runKey = runKey(workspace.getId(), runId);
        String connectorName = connectorName(request.connectorName(), runKey, fault);
        UUID pipelineId = stableUuid("pipeline", workspace.getId(), runId, fault);
        UUID datasourceId = stableUuid("datasource", workspace.getId(), runId, fault);
        Map<String, String> labels = labels(projectId, runKey, fault);

        assertNoExistingConnector(connectorName);
        GenericKubernetesResource connector = buildConnector(connectorName, labels, fault, projectId, runKey);
        k8s.resource(connector).inNamespace(namespace).create();

        return new SafeInjectionCreateResult(
                runId,
                fault.wireName(),
                fault.expectedRootCauseId(),
                pipelineId,
                datasourceId,
                false,
                connectorName,
                namespace,
                labels,
                OP_SCOPE);
    }

    public SafeInjectionCleanupResult cleanup(String projectId, String rawRunId) {
        String runId = requireRunId(rawRunId);
        WorkspaceEntity workspace = requireWorkspace(projectId);
        requireTestWorkspace(projectId, workspace);
        String runKey = runKey(workspace.getId(), runId);

        int deletedK8s = deleteK8sConnectors(runKey);
        List<String> residuals = residuals(runKey);
        return new SafeInjectionCleanupResult(runId, deletedK8s, 0, residuals.size(), residuals);
    }

    public SafeInjectionCleanupResult residualsOnly(String projectId, String rawRunId) {
        String runId = requireRunId(rawRunId);
        WorkspaceEntity workspace = requireWorkspace(projectId);
        requireTestWorkspace(projectId, workspace);
        String runKey = runKey(workspace.getId(), runId);
        List<String> residuals = residuals(runKey);
        return new SafeInjectionCleanupResult(runId, 0, 0, residuals.size(), residuals);
    }

    private int deleteK8sConnectors(String runKey) {
        int deleted = 0;
        var list = k8s.genericKubernetesResources("kafka.strimzi.io/v1", "KafkaConnector")
                .inNamespace(namespace)
                .withLabel(LABEL_SAFE, "true")
                .withLabel(LABEL_RUN, runKey)
                .list();
        for (GenericKubernetesResource item : list.getItems()) {
            String name = item.getMetadata() == null ? null : item.getMetadata().getName();
            if (name == null || !name.startsWith(CONNECTOR_PREFIX) || !isSafeResource(item, runKey)) {
                continue;
            }
            k8s.resource(connectorTemplate(name)).inNamespace(namespace).delete();
            deleted++;
        }
        return deleted;
    }

    private List<String> residuals(String runKey) {
        List<String> out = new ArrayList<>();
        var list = k8s.genericKubernetesResources("kafka.strimzi.io/v1", "KafkaConnector")
                .inNamespace(namespace)
                .withLabel(LABEL_SAFE, "true")
                .withLabel(LABEL_RUN, runKey)
                .list();
        for (GenericKubernetesResource item : list.getItems()) {
            if (isSafeResource(item, runKey) && item.getMetadata() != null) {
                out.add("k8s:KafkaConnector/" + item.getMetadata().getName());
            }
        }
        return List.copyOf(out);
    }

    private void assertNoExistingConnector(String connectorName) {
        GenericKubernetesResource existing = k8s.resource(connectorTemplate(connectorName)).inNamespace(namespace).get();
        if (existing != null) {
            throw new IllegalArgumentException("connector name collides with existing resource: " + connectorName);
        }
    }

    private GenericKubernetesResource buildConnector(String name,
                                                     Map<String, String> labels,
                                                     SafeInjectionFault fault,
                                                     String projectId,
                                                     String runKey) {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("class", connectorClass(fault));
        spec.put("tasksMax", 1);
        spec.put("config", connectorConfig(fault, projectId, runKey));

        var builder = new GenericKubernetesResourceBuilder()
                .withApiVersion("kafka.strimzi.io/v1")
                .withKind("KafkaConnector")
                .withNewMetadata()
                    .withName(name)
                    .withNamespace(namespace)
                    .addToLabels(labels)
                .endMetadata()
                .addToAdditionalProperties("spec", spec);
        if (fault == SafeInjectionFault.LAG || fault == SafeInjectionFault.NO_FAULT) {
            builder.editMetadata().addToAnnotations("strimzi.io/pause", "true").endMetadata();
        }
        return builder.build();
    }

    private Map<String, Object> connectorConfig(SafeInjectionFault fault, String projectId, String runKey) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("topics", safeTopic(projectId, runKey, fault));
        config.put("key.converter", "org.apache.kafka.connect.storage.StringConverter");
        config.put("value.converter", "org.apache.kafka.connect.json.JsonConverter");
        config.put("value.converter.schemas.enable", "false");
        config.put("errors.tolerance", "none");
        switch (fault) {
            case AUTH -> {
                config.put("connection.url", "jdbc:postgresql://safeinject.invalid:5432/safeinject");
                config.put("connection.user", "safeinject_invalid_user");
                config.put("connection.password", "safeinject_invalid_password");
                config.put("insert.mode", "insert");
                config.put("auto.create", "false");
                config.put("connection.attempts", "1");
                config.put("retry.backoff.ms", "100");
            }
            case SCHEMA -> {
                config.put("value.converter", "com.bifrost.safeinject.NoSuchConverter");
                config.put("file", "/tmp/bifrost-safeinject-schema-" + runKey + ".log");
            }
            case LAG -> config.put("file", "/tmp/bifrost-safeinject-lag-" + runKey + ".log");
            case SINK_FAIL -> {
                config.put("connection.url", "jdbc:postgresql://safeinject-sink.invalid:5432/safeinject");
                config.put("connection.user", "safeinject");
                config.put("connection.password", "safeinject");
                config.put("insert.mode", "insert");
                config.put("auto.create", "false");
                config.put("connection.attempts", "1");
                config.put("retry.backoff.ms", "100");
            }
            case NO_FAULT -> config.put("file", "/tmp/bifrost-safeinject-control-" + runKey + ".log");
        }
        return config;
    }

    private String connectorClass(SafeInjectionFault fault) {
        return switch (fault) {
            case AUTH, SINK_FAIL -> "io.confluent.connect.jdbc.JdbcSinkConnector";
            case SCHEMA, LAG, NO_FAULT -> "org.apache.kafka.connect.file.FileStreamSinkConnector";
        };
    }

    private Map<String, String> labels(String projectId, String runKey, SafeInjectionFault fault) {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put(LABEL_CLUSTER, connectCluster);
        labels.put(LABEL_SAFE, "true");
        labels.put(LABEL_RUN, runKey);
        labels.put(LABEL_FAULT, fault.wireName());
        labels.put(LABEL_PROJECT, labelValue(projectId));
        return labels;
    }

    private GenericKubernetesResource connectorTemplate(String name) {
        return new GenericKubernetesResourceBuilder()
                .withApiVersion("kafka.strimzi.io/v1")
                .withKind("KafkaConnector")
                .withNewMetadata()
                    .withName(name)
                    .withNamespace(namespace)
                .endMetadata()
                .build();
    }

    private boolean isSafeResource(GenericKubernetesResource resource, String runKey) {
        if (resource.getMetadata() == null || resource.getMetadata().getLabels() == null) {
            return false;
        }
        Map<String, String> labels = resource.getMetadata().getLabels();
        if (!"true".equals(labels.get(LABEL_SAFE))) {
            return false;
        }
        return runKey == null || runKey.equals(labels.get(LABEL_RUN));
    }

    private WorkspaceEntity requireWorkspace(String projectId) {
        return workspaceRepository.findByNamespace(projectId)
                .or(() -> parseUuid(projectId).flatMap(workspaceRepository::findById))
                .orElseThrow(() -> new ApiException(ErrorCode.WORKSPACE_NOT_FOUND,
                        "프로젝트를 찾을 수 없습니다: " + projectId));
    }

    private void requireTestWorkspace(String projectId, WorkspaceEntity workspace) {
        TreeSet<String> candidates = new TreeSet<>();
        candidates.add(projectId);
        candidates.add(workspace.getId().toString());
        candidates.add(nullToEmpty(workspace.getNamespace()));
        candidates.add(nullToEmpty(workspace.getName()));
        boolean allowed = candidates.stream()
                .map(v -> v.toLowerCase(Locale.ROOT))
                .anyMatch(v -> allowedProjects.contains(v)
                        || v.startsWith("safeinject")
                        || v.startsWith("e2e-rca-test"));
        if (!allowed) {
            throw new ApiException(ErrorCode.WORKSPACE_FORBIDDEN,
                    "safe injection is allowed only for configured test workspaces");
        }
    }

    private String connectorName(String requested, String runKey, SafeInjectionFault fault) {
        String generated = CONNECTOR_PREFIX + runKey + "-" + fault.code();
        String name = requested == null || requested.isBlank() ? generated : requested.trim();
        if (!generated.equals(name)) {
            throw new IllegalArgumentException("connectorName override is not supported for residual-safe cleanup");
        }
        if (!name.startsWith(CONNECTOR_PREFIX)) {
            throw new IllegalArgumentException("safe connector name must start with " + CONNECTOR_PREFIX);
        }
        if (name.length() > 63 || !DNS_LABEL.matcher(name).matches()) {
            throw new IllegalArgumentException("safe connector name must be a DNS-1123 label up to 63 chars");
        }
        return name;
    }

    private String requireRunId(String runId) {
        if (runId == null || runId.isBlank() || !RUN_ID.matcher(runId.trim()).matches()) {
            throw new IllegalArgumentException("runId must match [A-Za-z0-9._:-]{1,80}");
        }
        return runId.trim();
    }

    private UUID stableUuid(String kind, UUID workspaceId, String runId, SafeInjectionFault fault) {
        String key = "safeinject:" + kind + ":" + workspaceId + ":" + runId + ":" + fault.wireName();
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }

    private String runKey(UUID workspaceId, String runId) {
        return "r" + sha256(workspaceId + ":" + runId).substring(0, 12);
    }

    private String safeTopic(String projectId, String runKey, SafeInjectionFault fault) {
        return "safeinject." + labelValue(projectId) + "." + runKey + "." + fault.code();
    }

    private String labelValue(String value) {
        String normalized = nullToEmpty(value)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_.-]", "-");
        if (normalized.isBlank()) {
            normalized = "unknown";
        }
        if (normalized.length() <= 63) {
            return normalized;
        }
        return normalized.substring(0, 50) + "-" + sha256(normalized).substring(0, 12);
    }

    private static Optional<UUID> parseUuid(String value) {
        try {
            return Optional.of(UUID.fromString(value));
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private static Set<String> parseAllowedProjects(String raw) {
        Set<String> out = new TreeSet<>();
        if (raw != null) {
            for (String part : raw.split(",")) {
                if (!part.isBlank()) {
                    out.add(part.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        return Set.copyOf(out);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String nonBlankOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
