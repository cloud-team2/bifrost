package com.bifrost.ops.incident.feedback.dto;

/**
 * #964 RCA 피드백 제출 요청.
 *
 * @param verdict              accepted / rejected / corrected (대소문자 무관)
 * @param runId               피드백 대상 RCA run id(선택)
 * @param rcaRootCauseId      RCA 가 제시한 root cause(피드백 시점 스냅샷, 선택)
 * @param rcaConfidence       RCA confidence 스냅샷(선택)
 * @param correctedRootCauseId corrected 일 때 올바른 root cause(필수)
 * @param triggerLabel        근본 trigger 라벨(선택)
 * @param symptomLabel        근접 증상 라벨(선택)
 */
public record RcaFeedbackRequest(
        String verdict,
        String runId,
        String rcaRootCauseId,
        Double rcaConfidence,
        String correctedRootCauseId,
        String triggerLabel,
        String symptomLabel
) {
}
