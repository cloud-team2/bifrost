package com.bifrost.ops.incident.feedback;

import com.bifrost.ops.incident.AiServiceEndpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * #982 운영자 RCA 평결(채택/거부/수정)을 ai-service gold set 으로 승격(promote)한다.
 *
 * <p>로컬 {@code rca_feedback} 영속화가 SoT 이고, gold set 적재는 best-effort 부가 작업이다.
 * ai-service 가 불가하거나 미설정이어도 피드백 제출 자체는 실패하지 않도록 비동기·예외흡수로 처리한다.
 * RCA 평가(AC@k)·confidence 캘리브레이션(ECE) 용 gold set 은 ai-service(agentdb)가 소유한다.
 */
@Service
public class GoldSetPromoter {

    private static final Logger log = LoggerFactory.getLogger(GoldSetPromoter.class);

    private final RestClient restClient;
    private final AiServiceEndpoint aiServiceEndpoint;
    private final Executor executor;
    private final AtomicBoolean disabledWarningLogged = new AtomicBoolean(false);

    public GoldSetPromoter(@Value("${ai-service.url:}") String aiServiceUrl,
                           RestClient.Builder restClientBuilder,
                           @Qualifier("applicationTaskExecutor") Executor executor) {
        this.aiServiceEndpoint = AiServiceEndpoint.from(aiServiceUrl);
        this.restClient = aiServiceEndpoint.configured()
                ? restClientBuilder.baseUrl(aiServiceEndpoint.baseUrl()).build()
                : restClientBuilder.build();
        this.executor = executor;
    }

    /**
     * 운영자 평결을 비동기로 gold set 에 승격한다.
     *
     * @param incidentId          인시던트 id
     * @param verdict             accepted / rejected / corrected (소문자)
     * @param reviewedBy          평결한 운영자 식별자(이메일 등)
     * @param predictedRootCauseId RCA 가 예측한 root cause(스냅샷, 선택)
     * @param correctedRootCauseId 정정된 root cause(corrected 시, 선택)
     * @param runId               대상 RCA run id(선택)
     * @param trigger             trigger 라벨(선택)
     * @param symptom             symptom 라벨(선택)
     */
    public void promote(UUID incidentId, String verdict, String reviewedBy,
                        String predictedRootCauseId, String correctedRootCauseId,
                        String runId, String trigger, String symptom) {
        if (!aiServiceEndpoint.configured()) {
            if (disabledWarningLogged.compareAndSet(false, true)) {
                log.warn("[rca-feedback] gold set promotion disabled: {}", aiServiceEndpoint.disabledReason());
            }
            return;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("incident_id", incidentId.toString());
        body.put("verdict", verdict == null ? null : verdict.toLowerCase());
        body.put("reviewed_by", reviewedBy);
        body.put("predicted_root_cause_id", predictedRootCauseId);
        body.put("corrected_root_cause_id", correctedRootCauseId);
        body.put("run_id", runId);
        body.put("trigger", trigger);
        body.put("symptom", symptom);
        executor.execute(() -> send(incidentId, body));
    }

    private void send(UUID incidentId, Map<String, Object> body) {
        try {
            restClient.post()
                    .uri("/api/v1/agent/gold-set/promote")
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            log.info("[rca-feedback] gold set 승격: incidentId={}", incidentId);
        } catch (RestClientException e) {
            // best-effort — 로컬 rca_feedback 은 이미 저장됨. 승격 실패는 경고만.
            log.warn("[rca-feedback] gold set 승격 실패: incidentId={} cause={}", incidentId, e.getMessage());
        }
    }
}
