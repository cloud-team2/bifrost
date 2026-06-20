package com.bifrost.ops.monitoring.sli;

import com.bifrost.ops.global.common.error.ApiException;
import com.bifrost.ops.global.common.error.ErrorCode;

import java.util.Arrays;
import java.util.Locale;

/** 사용자 영향 중심 SLI 종류(#891). */
public enum UserImpactSliType {
    DATA_FRESHNESS(
            "data_freshness",
            "데이터 신선도",
            "소스 변경 이벤트가 허용 지연 안에 Kafka/Connect 처리 경로로 유입된 비율",
            "source event observed while source watermark delay <= 5m",
            "all source events observed in the window",
            "ratio",
            0.999),
    END_TO_END_LATENCY(
            "end_to_end_latency",
            "End-to-end latency",
            "소스 변경부터 sink 소비까지 허용 지연 안에 처리된 이벤트 비율",
            "pipeline event delivered while end-to-end delay <= 60s",
            "all pipeline events observed in the window",
            "ratio",
            0.995),
    PROCESSING_SUCCESS_RATE(
            "processing_success_rate",
            "처리 성공률",
            "에러 없이 처리된 CDC/Connect 레코드 비율",
            "records processed without Debezium/Connect error",
            "all records attempted by source/sink connectors",
            "ratio",
            0.999),
    DATA_COMPLETENESS(
            "data_completeness",
            "데이터 완전성",
            "Kafka에 유입된 레코드 중 sink consumer group이 소비한 레코드 비율",
            "sink committed records",
            "topic ingress records",
            "ratio",
            0.999),
    PROVISIONING_SUCCESS_RATE(
            "provisioning_success_rate",
            "Provisioning 성공률",
            "생성된 파이프라인 중 정상 ACTIVE 상태로 수렴한 비율",
            "pipelines in ACTIVE lifecycle state",
            "all provisioned pipelines",
            "ratio",
            0.99);

    private final String apiName;
    private final String displayName;
    private final String description;
    private final String goodEvent;
    private final String totalEvent;
    private final String unit;
    private final double targetRatio;

    UserImpactSliType(String apiName,
                      String displayName,
                      String description,
                      String goodEvent,
                      String totalEvent,
                      String unit,
                      double targetRatio) {
        this.apiName = apiName;
        this.displayName = displayName;
        this.description = description;
        this.goodEvent = goodEvent;
        this.totalEvent = totalEvent;
        this.unit = unit;
        this.targetRatio = targetRatio;
    }

    public String apiName() {
        return apiName;
    }

    public String displayName() {
        return displayName;
    }

    public String description() {
        return description;
    }

    public String goodEvent() {
        return goodEvent;
    }

    public String totalEvent() {
        return totalEvent;
    }

    public String unit() {
        return unit;
    }

    public double targetRatio() {
        return targetRatio;
    }

    public static UserImpactSliType parse(String raw) {
        String normalized = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT).replace("-", "_");
        return Arrays.stream(values())
                .filter(type -> type.apiName.equals(normalized) || type.name().equalsIgnoreCase(normalized))
                .findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_FAILED, "지원하지 않는 SLI type: " + raw));
    }
}
