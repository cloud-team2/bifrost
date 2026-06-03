package com.bifrost.ops.provisioning.config;

import com.bifrost.ops.pipeline.ConnectorStatusUpdate;
import com.bifrost.ops.pipeline.PipelineStatusService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * real 모드({@code provisioning.mode=real})에서 {@link PipelineStatusService} 구현이 아직
 * 없을 때를 위한 fallback 설정(#16).
 *
 * <p>watcher({@code KafkaConnectorWatcher})는 real 모드에서만 켜지고 {@link PipelineStatusService}
 * 빈을 필수로 주입받는다. 권세빈의 pipeline 도메인 구현이 빈으로 존재하면 그쪽이 우선되고
 * ({@link ConditionalOnMissingBean}), 없으면 이 log-only 구현이 주입되어 real 모드 smoke가
 * 기동 실패 없이 가능하다. 운영 구현이 아니므로 상태를 로그로만 남긴다.
 */
@Configuration
@ConditionalOnProperty(name = "provisioning.mode", havingValue = "real")
public class PipelineStatusFallbackConfig {

    private static final Logger log = LoggerFactory.getLogger(PipelineStatusFallbackConfig.class);

    @Bean
    @ConditionalOnMissingBean(PipelineStatusService.class)
    public PipelineStatusService loggingPipelineStatusService() {
        log.warn("PipelineStatusService 구현 빈이 없어 log-only fallback을 사용합니다 "
                + "(real 모드 smoke 전용, pipeline row 갱신 없음).");
        return new LoggingPipelineStatusService();
    }

    /** 상태 변경을 로그로만 남기는 fallback 구현. pipeline row/event/SSE는 갱신하지 않는다. */
    static class LoggingPipelineStatusService implements PipelineStatusService {
        @Override
        public void applyConnectorStatus(ConnectorStatusUpdate update) {
            log.info("[status-fallback] connector={}, connectorState={}, pipelineStatus={}, "
                            + "failedTasks={}/{}",
                    update.connectorName(), update.connectorState(), update.pipelineStatus(),
                    update.failedTasks(), update.totalTasks());
        }
    }
}
