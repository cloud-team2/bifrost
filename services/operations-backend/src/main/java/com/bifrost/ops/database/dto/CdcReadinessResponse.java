package com.bifrost.ops.database.dto;

import com.bifrost.ops.database.cdc.CdcReadinessStatus;

import java.util.List;

/**
 * CDC 준비도 점검 결과(#29, FR-015, database-registry.md §4.2).
 *
 * @param overallStatus 항목 중 가장 심각한 수준(BLOCKED &gt; WARNING &gt; OK)
 * @param checks        항목별 결과
 */
public record CdcReadinessResponse(
        CdcReadinessStatus overallStatus,
        List<CdcCheck> checks
) {

    /**
     * @param name     점검 항목 이름
     * @param status   항목 상태
     * @param actual   실제 값
     * @param expected 기대 값
     * @param hint     해결 가이드(없으면 null)
     */
    public record CdcCheck(
            String name,
            CdcReadinessStatus status,
            String actual,
            String expected,
            String hint
    ) {
        public static CdcCheck ok(String name, String actual, String expected) {
            return new CdcCheck(name, CdcReadinessStatus.OK, actual, expected, null);
        }

        public static CdcCheck of(String name, CdcReadinessStatus status, String actual,
                                  String expected, String hint) {
            return new CdcCheck(name, status, actual, expected, status == CdcReadinessStatus.OK ? null : hint);
        }
    }
}
