package com.bifrost.ops.pipeline.status;

import com.bifrost.ops.pipeline.PipelineStatusService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 프로비저닝 타임아웃 스윕(#155 백스톱).
 *
 * <p>{@code creating}은 "곧 성공할 진행 중"만 의미해야 하며 막다른 상태가 되면 안 된다. NotReady
 * condition조차 안 뜨고(예: watch 유실, operator 무응답) 그냥 멈춘 경우까지 커버하기 위해, 일정 시간
 * 이상 {@code creating}에 머문 파이프라인을 주기적으로 {@code error}로 전이한다.
 */
@Component
public class ProvisioningTimeoutJob {

    private static final Logger log = LoggerFactory.getLogger(ProvisioningTimeoutJob.class);

    private final PipelineStatusService statusService;
    private final Duration timeout;

    public ProvisioningTimeoutJob(
            PipelineStatusService statusService,
            @Value("${pipeline.provisioning-timeout:PT5M}") Duration timeout) {
        this.statusService = statusService;
        this.timeout = timeout;
    }

    /** 기본 60초마다 스윕. */
    @Scheduled(fixedDelayString = "${pipeline.provisioning-timeout-check-ms:60000}")
    public void sweep() {
        try {
            statusService.failTimedOutCreating(timeout);
        } catch (RuntimeException e) {
            log.warn("프로비저닝 타임아웃 스윕 실패(무시): {}", e.getMessage());
        }
    }
}
