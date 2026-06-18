package com.bifrost.ops.internalops.dto;

import com.bifrost.ops.governance.audit.persistence.entity.AuditEventEntity;
import com.bifrost.ops.governance.changemanagement.persistence.entity.ChangeTicketEntity;
import com.bifrost.ops.pipeline.persistence.entity.PipelineEntity;
import com.bifrost.ops.provisioning.persistence.entity.ConnectorEntity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * get_recent_changes(ai-service get_deployments) tool의 result payload.
 *
 * <p>프로젝트(workspace) 단위의 최근 파이프라인 변경 이력을 시간 역순으로 담는다.
 * ai-service {@code DeploymentsData.changes[] = {changeId, type, description, changedAt}}
 * 계약(#390 정합)에 맞춰 camelCase 필드를 노출한다.
 *
 * <p>현재 별도의 배포 이력/changelog 테이블이 없으므로 {@link PipelineEntity}의
 * lifecycle 타임스탬프(createdAt·statusUpdatedAt)에서 변경 이벤트를 합성한다.
 * (Future Work: git commit·helm release·kustomize patch 등 실 배포 source 연계)
 */
public record RecentChangesResult(
        List<Change> changes,
        String summary
) {

    /** 변경 이벤트 1건. */
    public record Change(
            String changeId,
            String type,
            String description,
            Instant changedAt
    ) {}

    /**
     * 파이프라인 목록에서 변경 이벤트를 만들고 changedAt 역순(최신 우선)으로 정렬한다.
     *
     * @param pipelines tenant(workspace) 소속 파이프라인
     * @param limit     반환할 최대 변경 건수(null·0 이하면 전체)
     */
    public static RecentChangesResult of(List<PipelineEntity> pipelines, Integer limit) {
        return of(pipelines, List.of(), List.of(), List.of(), List.of(), limit);
    }

    /**
     * live에 존재하는 변경 source만 병합한다.
     *
     * <p>connector config diff/schema registry/credential rotation 전용 이력은 현재 live source가
     * 없으므로 여기서 만들지 않는다. 호출자는 KafkaConnect/KafkaConnector CR snapshot처럼 실측 가능한
     * runtime metadata만 {@code runtimeChanges}로 넘긴다.
     */
    public static RecentChangesResult of(
            List<PipelineEntity> pipelines,
            List<ConnectorEntity> connectors,
            List<ChangeTicketEntity> changeTickets,
            List<AuditEventEntity> auditEvents,
            List<Change> runtimeChanges,
            Integer limit) {
        List<Change> changes = new ArrayList<>();
        for (PipelineEntity p : nullSafe(pipelines)) {
            changes.addAll(toChanges(p));
        }
        for (ConnectorEntity connector : nullSafe(connectors)) {
            changes.addAll(toConnectorChanges(connector));
        }
        for (ChangeTicketEntity ticket : nullSafe(changeTickets)) {
            changes.add(toChange(ticket));
        }
        for (AuditEventEntity audit : nullSafe(auditEvents)) {
            changes.add(toChange(audit));
        }
        changes.addAll(nullSafe(runtimeChanges));
        changes.sort(Comparator.comparing(
                Change::changedAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        if (limit != null && limit > 0 && changes.size() > limit) {
            changes = changes.subList(0, limit);
        }
        List<Change> immutable = List.copyOf(changes);
        return new RecentChangesResult(immutable, summarize(immutable));
    }

    /** 단일 파이프라인 → 생성·상태전이 변경 이벤트. */
    private static List<Change> toChanges(PipelineEntity p) {
        List<Change> changes = new ArrayList<>(2);

        changes.add(new Change(
                p.getId() + ":created",
                "PIPELINE_CREATED",
                "파이프라인 '" + p.getName() + "' 생성"
                        + " (pattern=" + p.getPattern()
                        + ", topic=" + p.getTopicName() + ")",
                p.getCreatedAt()));

        Instant statusUpdatedAt = p.getStatusUpdatedAt();
        if (statusUpdatedAt != null && !statusUpdatedAt.equals(p.getCreatedAt())) {
            String message = p.getStatusMessage();
            String description = "상태 전이 → " + p.getStatus()
                    + (message != null && !message.isBlank() ? ": " + message : "");
            changes.add(new Change(
                    p.getId() + ":status:" + p.getStatus(),
                    "STATUS_CHANGE",
                    description,
                    statusUpdatedAt));
        }

        return changes;
    }

    private static List<Change> toConnectorChanges(ConnectorEntity connector) {
        List<Change> changes = new ArrayList<>(2);
        changes.add(new Change(
                connector.getId() + ":connector-created",
                "CONNECTOR_CONFIG_CREATED",
                "최근 pipeline/connector config 변경 evidence: KafkaConnector config materialized for connector '"
                        + connector.getCrName() + "'"
                        + " (kind=" + connector.getKind()
                        + ", class=" + connector.getConnectorClass()
                        + ", tasksMax=" + connector.getTasksMax() + ")",
                connector.getCreatedAt()));

        Instant updatedAt = connector.getUpdatedAt();
        if (updatedAt != null && (connector.getCreatedAt() == null || !updatedAt.equals(connector.getCreatedAt()))) {
            String lastError = connector.getLastError();
            changes.add(new Change(
                    connector.getId() + ":connector-status:" + connector.getState(),
                    "CONNECTOR_STATUS_OBSERVED",
                    "connector task status " + connector.getState()
                            + " observed for '" + connector.getCrName() + "'"
                            + (lastError != null && !lastError.isBlank() ? ": " + sanitizeDetail(lastError) : ""),
                    updatedAt));
        }
        return changes;
    }

    private static Change toChange(ChangeTicketEntity ticket) {
        return new Change(
                ticket.getId() + ":change-ticket",
                "CHANGE_TICKET",
                "recent change ticket '" + safe(ticket.getTitle()) + "'"
                        + (notBlank(ticket.getScopeOperation()) ? " scopeOperation=" + ticket.getScopeOperation() : "")
                        + " status=" + ticket.getStatus()
                        + (ticket.getApprovedAt() != null ? " approvedAt=" + ticket.getApprovedAt() : ""),
                ticket.getCreatedAt());
    }

    private static Change toChange(AuditEventEntity audit) {
        return new Change(
                audit.getId() + ":audit",
                "AUDIT_EVENT",
                "recent audit event action=" + audit.getAction()
                        + (notBlank(audit.getTargetType()) ? " targetType=" + audit.getTargetType() : "")
                        + (audit.getTargetId() != null ? " targetId=" + audit.getTargetId() : "")
                        + (notBlank(audit.getDetail()) ? " detail=" + sanitizeDetail(audit.getDetail()) : ""),
                audit.getCreatedAt());
    }

    private static String summarize(List<Change> changes) {
        if (changes.isEmpty()) {
            return "live change evidence: 0 changes matched";
        }
        Map<String, Integer> byType = new LinkedHashMap<>();
        int configEvidence = 0;
        for (Change change : changes) {
            byType.merge(change.type(), 1, Integer::sum);
            if (isConfigEvidence(change)) {
                configEvidence++;
            }
        }
        return "live change evidence: " + changes.size()
                + " changes from metadb/kubernetes live sources"
                + " (types=" + byType
                + (configEvidence > 0
                        ? ", 최근 pipeline/connector config 변경 evidence count=" + configEvidence
                        : "")
                + ")";
    }

    private static boolean isConfigEvidence(Change change) {
        String type = change.type() == null ? "" : change.type().toUpperCase();
        String description = change.description() == null ? "" : change.description();
        return type.contains("CONFIG") || description.contains("pipeline/connector config 변경");
    }

    private static String sanitizeDetail(String raw) {
        if (raw == null) {
            return "";
        }
        String compact = raw.replaceAll("\\s+", " ").trim();
        compact = compact.replaceAll("(?i)(password|passwd|secret|token|credential)\\s*[:=]\\s*[^\\s,;]+", "$1=[REDACTED]");
        compact = compact.replaceAll("(?i)\\b(password|passwd|secret|token|credential)\\b", "[REDACTED]");
        if (compact.length() > 180) {
            compact = compact.substring(0, 177) + "...";
        }
        return compact;
    }

    private static String safe(String raw) {
        return raw == null ? "" : sanitizeDetail(raw);
    }

    private static boolean notBlank(String raw) {
        return raw != null && !raw.isBlank();
    }

    private static <T> List<T> nullSafe(List<T> list) {
        return list == null ? List.of() : list;
    }
}
