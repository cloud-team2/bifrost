package com.bifrost.ops.internalops.dto;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record LogSearchResult(
        List<Map<String, Object>> logs,
        int total,
        String note,
        String summary,
        List<StructuredLogEvidence> evidence) {

    private static final Pattern TASK_ID = Pattern.compile("(?i)(?:task(?:\\s|_|-)?id|task)\\D{0,8}(\\d+)");

    public record StructuredLogEvidence(
            String errorClass,
            String stage,
            String connector,
            Integer taskId,
            String matchedRequiredToken,
            int count,
            String firstSeen,
            String lastSeen
    ) {}

    public static LogSearchResult of(List<Map<String, Object>> logs) {
        return of(logs, List.of());
    }

    public static LogSearchResult of(List<Map<String, Object>> logs, List<String> connectorNames) {
        List<Map<String, Object>> safeLogs = logs == null ? List.of() : logs;
        List<StructuredLogEvidence> evidence = extractEvidence(safeLogs, connectorNames);
        return new LogSearchResult(safeLogs, safeLogs.size(), null, summarize(safeLogs.size(), evidence), evidence);
    }

    public static LogSearchResult stub() {
        String note = "log source pending — dependency on monitoring integration";
        return new LogSearchResult(List.of(), 0, note, note, List.of());
    }

    private static List<StructuredLogEvidence> extractEvidence(
            List<Map<String, Object>> logs,
            List<String> connectorNames) {
        Map<EvidenceKey, MutableEvidence> grouped = new LinkedHashMap<>();
        List<String> connectors = connectorNames == null ? List.of() : connectorNames;
        for (Map<String, Object> log : logs) {
            String line = stringValue(log.get("line"));
            ErrorSignal signal = classify(line);
            if (signal == null) {
                continue;
            }
            String connector = findConnector(line, connectors);
            String stage = inferStage(line, connector);
            Integer taskId = findTaskId(line);
            String seen = observedAt(log.get("ts"));
            EvidenceKey key = new EvidenceKey(signal.errorClass(), stage, connector, taskId, signal.requiredToken());
            grouped.computeIfAbsent(key, MutableEvidence::new).add(seen);
        }
        return grouped.values().stream()
                .map(MutableEvidence::toEvidence)
                .sorted(Comparator.comparing(StructuredLogEvidence::count).reversed())
                .toList();
    }

    private static ErrorSignal classify(String rawLine) {
        String line = rawLine == null ? "" : rawLine.toLowerCase();
        if (containsAny(line, "config validation", "invalid option", "unknown config",
                "invalid converter", "configurationexception", "validateexception")) {
            return new ErrorSignal("config", "config validation error 또는 invalid option log");
        }
        if (containsAny(line, "accessdenied", "permission denied", "token expired",
                "authentication failed", "authorization failed", "password authentication failed",
                "sasl authentication", "인증 실패", "권한 거부", "비밀번호 인증 실패")) {
            return new ErrorSignal("auth", "auth/permission error log");
        }
        if (hasSchemaFailureSignal(line)) {
            return new ErrorSignal("schema", "serialization/deserialization/schema error");
        }
        if (containsAny(line, "constraint", "duplicate key", "not-null", "not null",
                "foreign key", "sqlintegrityconstraintviolation", "batchupdateexception",
                "unique violation")) {
            return new ErrorSignal("constraint", "sink constraint 또는 duplicate key error");
        }
        if (containsAny(line, "connection refused", "no route to host", "connectexception",
                "연결 실패", "네트워크")) {
            return new ErrorSignal("timeout",
                    "Bifrost에서 source endpoint reachability 실패 connection refused no route to host 네트워크 도달 실패");
        }
        if (containsAny(line, "timeout", "timed out", "sockettimeoutexception", "타임아웃")) {
            return new ErrorSignal("timeout", "pipeline extract/read 단계 timeout log");
        }
        if (containsAny(line, "rebalance", "revoked", "assigned", "rejoining group",
                "syncgroup", "join group")) {
            return new ErrorSignal("rebalance", "Connect worker rebalance 이벤트 반복");
        }
        return null;
    }

    private static String summarize(int total, List<StructuredLogEvidence> evidence) {
        if (evidence.isEmpty()) {
            return "structured log evidence: 0 classified matches (logs=" + total + ")";
        }
        List<String> parts = new ArrayList<>();
        for (StructuredLogEvidence item : evidence.stream().limit(4).toList()) {
            parts.add(item.matchedRequiredToken()
                    + " worker log class=" + item.errorClass()
                    + (item.stage() != null ? " stage=" + item.stage() : "")
                    + (item.connector() != null ? " connector=" + item.connector() : "")
                    + (item.taskId() != null ? " task=" + item.taskId() : "")
                    + " count=" + item.count()
                    + (item.firstSeen() != null && item.lastSeen() != null
                            ? " window=" + item.firstSeen() + ".." + item.lastSeen() : ""));
        }
        return "structured log evidence: " + String.join("; ", parts);
    }

    private static String inferStage(String line, String connector) {
        String normalized = line == null ? "" : line.toLowerCase();
        if (connector != null && connector.toLowerCase().endsWith("-sink")) {
            return "sink";
        }
        if (connector != null && connector.toLowerCase().endsWith("-source")) {
            return "source";
        }
        if (containsAny(normalized, "sink", "jdbc", "write")) {
            return "sink";
        }
        if (containsAny(normalized, "source", "debezium", "extract", "read")) {
            return "source";
        }
        return null;
    }

    private static String findConnector(String line, List<String> connectorNames) {
        if (line == null || connectorNames == null) {
            return null;
        }
        String normalized = line.toLowerCase();
        return connectorNames.stream()
                .filter(Objects::nonNull)
                .filter(name -> !name.isBlank())
                .sorted(Comparator.comparingInt(String::length).reversed())
                .filter(name -> tokenBoundaryMatch(normalized, name))
                .findFirst()
                .orElse(null);
    }

    private static boolean hasSchemaFailureSignal(String line) {
        boolean schemaContext = containsAny(line, "deserialization", "serialization", "schema",
                "converter", "스키마", "역직렬화");
        boolean failureToken = containsAny(line, "error", "exception", "failed", "failure",
                "mismatch", "incompatible", "invalid", "unable", "cannot", "not found",
                "unavailable", "오류", "실패", "불일치");
        boolean schemaFault = containsAny(line, "deserialization", "serialization", "schema mismatch",
                "incompatible schema", "schema error", "converter error", "invalid converter",
                "스키마 불일치", "역직렬화");
        return schemaContext && failureToken && schemaFault;
    }

    private static boolean tokenBoundaryMatch(String normalizedLine, String connectorName) {
        Pattern pattern = Pattern.compile("(?i)(^|[^A-Za-z0-9._-])"
                + Pattern.quote(connectorName)
                + "([^A-Za-z0-9._-]|$)");
        return pattern.matcher(normalizedLine).find();
    }

    private static Integer findTaskId(String line) {
        if (line == null) {
            return null;
        }
        Matcher matcher = TASK_ID.matcher(line);
        if (!matcher.find()) {
            return null;
        }
        try {
            return Integer.valueOf(matcher.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String observedAt(Object rawTs) {
        String value = stringValue(rawTs);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            long ns = Long.parseLong(value);
            return Instant.ofEpochSecond(ns / 1_000_000_000L, ns % 1_000_000_000L).toString();
        } catch (NumberFormatException e) {
            return value;
        }
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private record ErrorSignal(String errorClass, String requiredToken) {}

    private record EvidenceKey(
            String errorClass,
            String stage,
            String connector,
            Integer taskId,
            String requiredToken) {}

    private static final class MutableEvidence {
        private final EvidenceKey key;
        private int count;
        private String firstSeen;
        private String lastSeen;

        private MutableEvidence(EvidenceKey key) {
            this.key = key;
        }

        private void add(String seen) {
            count++;
            if (seen == null) {
                return;
            }
            if (firstSeen == null || seen.compareTo(firstSeen) < 0) {
                firstSeen = seen;
            }
            if (lastSeen == null || seen.compareTo(lastSeen) > 0) {
                lastSeen = seen;
            }
        }

        private StructuredLogEvidence toEvidence() {
            return new StructuredLogEvidence(
                    key.errorClass(),
                    key.stage(),
                    key.connector(),
                    key.taskId(),
                    key.requiredToken(),
                    count,
                    firstSeen,
                    lastSeen);
        }
    }
}
