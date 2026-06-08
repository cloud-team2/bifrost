package com.bifrost.ops.database.cdc;

import java.util.Collection;

/**
 * CDC 준비도 항목·전체 상태(database-registry.md §4.2). 선언 순서가 심각도(낮음→높음)다.
 *
 * <ul>
 *   <li>{@code OK} — CDC Source로 사용 준비 완료</li>
 *   <li>{@code WARNING} — 부분 미흡, 연결은 가능(경고 배지)</li>
 *   <li>{@code BLOCKED} — 파이프라인 Source 선택 불가</li>
 * </ul>
 */
public enum CdcReadinessStatus {
    OK,
    WARNING,
    BLOCKED;

    /** 가장 심각한 상태(BLOCKED &gt; WARNING &gt; OK). 비어 있으면 OK. */
    public static CdcReadinessStatus worst(Collection<CdcReadinessStatus> statuses) {
        CdcReadinessStatus worst = OK;
        for (CdcReadinessStatus s : statuses) {
            if (s.ordinal() > worst.ordinal()) {
                worst = s;
            }
        }
        return worst;
    }
}
