package com.bifrost.ops.pipeline;

import com.bifrost.ops.global.common.error.ApiException;
import com.bifrost.ops.global.common.error.ErrorCode;
import com.bifrost.ops.provisioning.dto.PipelinePattern;

/**
 * 프론트 표현(fan-out/direct)과 백엔드 {@link PipelinePattern}(FAN_OUT/DIRECT) 매핑(#71).
 *
 * <p>프론트는 {@code fan-out}/{@code direct}를 쓰고 도메인은 {@code FAN_OUT}/{@code DIRECT}를 쓴다.
 * 변환을 한 곳에 모아 표현 불일치로 인한 검증 오류를 막는다.
 */
public final class PipelinePatternCodec {

    private PipelinePatternCodec() {}

    /** 요청 문자열 → 도메인 패턴. 대소문자·하이픈/언더스코어를 모두 허용. 미지원 값은 VALIDATION_FAILED. */
    public static PipelinePattern parse(String raw) {
        if (raw == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "pattern이 필요합니다");
        }
        String normalized = raw.trim().toLowerCase().replace('-', '_');
        return switch (normalized) {
            case "fan_out", "fanout", "eda" -> PipelinePattern.FAN_OUT;
            case "direct", "cdc" -> PipelinePattern.DIRECT;
            default -> throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "지원하지 않는 pattern: " + raw + " (fan-out 또는 direct)");
        };
    }

    /** 도메인 패턴 → 프론트 표현. */
    public static String toApi(PipelinePattern pattern) {
        return pattern == PipelinePattern.FAN_OUT ? "fan-out" : "direct";
    }
}
