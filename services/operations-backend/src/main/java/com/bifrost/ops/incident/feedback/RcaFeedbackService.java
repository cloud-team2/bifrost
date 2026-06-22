package com.bifrost.ops.incident.feedback;

import com.bifrost.ops.global.common.error.ApiException;
import com.bifrost.ops.global.common.error.ErrorCode;
import com.bifrost.ops.incident.feedback.dto.RcaFeedbackRequest;
import com.bifrost.ops.incident.feedback.dto.RcaFeedbackResponse;
import com.bifrost.ops.incident.feedback.persistence.entity.RcaFeedbackEntity;
import com.bifrost.ops.incident.feedback.persistence.repository.RcaFeedbackRepository;
import com.bifrost.ops.incident.persistence.repository.IncidentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * #964 RCA 운영자 피드백(채택/거부/수정) 수집·조회. 별도 ML 모델 없이 데이터만 축적하며,
 * 누적된 피드백이 곧 RCA 평가(AC@k)·confidence 캘리브레이션(ECE)용 gold set 이 된다.
 */
@Service
public class RcaFeedbackService {

    private static final String ACCEPTED = "ACCEPTED";
    private static final String REJECTED = "REJECTED";
    private static final String CORRECTED = "CORRECTED";
    private static final Set<String> VERDICTS = Set.of(ACCEPTED, REJECTED, CORRECTED);

    private final RcaFeedbackRepository feedbackRepository;
    private final IncidentRepository incidentRepository;
    private final GoldSetPromoter goldSetPromoter;

    public RcaFeedbackService(RcaFeedbackRepository feedbackRepository,
                              IncidentRepository incidentRepository,
                              GoldSetPromoter goldSetPromoter) {
        this.feedbackRepository = feedbackRepository;
        this.incidentRepository = incidentRepository;
        this.goldSetPromoter = goldSetPromoter;
    }

    @Transactional
    public RcaFeedbackResponse submit(UUID tenantId, UUID incidentId, RcaFeedbackRequest request, String operator) {
        incidentRepository.findByIdAndTenantId(incidentId, tenantId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND,
                        "incident not found: " + incidentId));

        String verdict = request == null || request.verdict() == null ? "" : request.verdict().trim().toUpperCase();
        if (!VERDICTS.contains(verdict)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "유효하지 않은 verdict: " + (request == null ? null : request.verdict()) + " (accepted/rejected/corrected)");
        }
        String correctedRootCauseId = trimToNull(request.correctedRootCauseId());
        if (CORRECTED.equals(verdict) && correctedRootCauseId == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "corrected 피드백은 corrected_root_cause_id 가 필요합니다");
        }

        RcaFeedbackEntity entity = new RcaFeedbackEntity();
        entity.setTenantId(tenantId);
        entity.setIncidentId(incidentId);
        entity.setRunId(trimToNull(request.runId()));
        entity.setRcaRootCauseId(trimToNull(request.rcaRootCauseId()));
        entity.setRcaConfidence(request.rcaConfidence());
        entity.setVerdict(verdict);
        entity.setCorrectedRootCauseId(correctedRootCauseId);
        entity.setTriggerLabel(trimToNull(request.triggerLabel()));
        entity.setSymptomLabel(trimToNull(request.symptomLabel()));
        entity.setOperator(trimToNull(operator));

        RcaFeedbackEntity saved = feedbackRepository.save(entity);

        // #982 누적된 평결을 ai-service gold set 으로 승격(best-effort, 비동기). 로컬 영속화가 SoT.
        goldSetPromoter.promote(
                incidentId,
                verdict,
                saved.getOperator(),
                saved.getRcaRootCauseId(),
                saved.getCorrectedRootCauseId(),
                saved.getRunId(),
                saved.getTriggerLabel(),
                saved.getSymptomLabel());

        return RcaFeedbackResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public List<RcaFeedbackResponse> list(UUID tenantId, UUID incidentId) {
        return feedbackRepository.findByTenantIdAndIncidentIdOrderByCreatedAtDesc(tenantId, incidentId)
                .stream().map(RcaFeedbackResponse::from).toList();
    }

    /** 워크스페이스 전체 피드백 — RCA 평가·캘리브레이션(gold set) 연결 지점(#964 후속). */
    @Transactional(readOnly = true)
    public List<RcaFeedbackResponse> listForWorkspace(UUID tenantId) {
        return feedbackRepository.findByTenantIdOrderByCreatedAtDesc(tenantId)
                .stream().map(RcaFeedbackResponse::from).toList();
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
