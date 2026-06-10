package com.bifrost.ops.internalops.dto;

import com.bifrost.ops.pipeline.persistence.entity.PipelineEntity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

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
        List<Change> changes
) {

    /** 변경 이벤트 1건. */
    public record Change(
            String changeId,
            String type,
            String description,
            Instant changedAt
    ) {}

    /**
     * 파이프라인 목록에서 변경 이벤트를 합성하고 changedAt 역순(최신 우선)으로 정렬한다.
     *
     * @param pipelines tenant(workspace) 소속 파이프라인
     * @param limit     반환할 최대 변경 건수(null·0 이하면 전체)
     */
    public static RecentChangesResult of(List<PipelineEntity> pipelines, Integer limit) {
        List<Change> changes = new ArrayList<>();
        for (PipelineEntity p : pipelines) {
            changes.addAll(toChanges(p));
        }
        changes.sort(Comparator.comparing(
                Change::changedAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        if (limit != null && limit > 0 && changes.size() > limit) {
            changes = changes.subList(0, limit);
        }
        return new RecentChangesResult(List.copyOf(changes));
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
}
