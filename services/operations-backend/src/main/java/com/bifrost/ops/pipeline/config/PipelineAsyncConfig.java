package com.bifrost.ops.pipeline.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;

/**
 * 파이프라인 생성 후 {@code creating → active} 비동기 전이용 실행기(#71).
 *
 * <p>트랜잭션 커밋 후 mock provisioner 상태를 보고 active로 전이하는 짧은 작업을 실행한다.
 * 전용 빈으로 분리해 다른 {@code TaskExecutor}와 충돌하지 않게 한다(#70에서 상태 전이가
 * PipelineStatusService로 이관되면 이 실행기 사용처도 함께 정리된다).
 */
@Configuration
public class PipelineAsyncConfig {

    @Bean("pipelineActivationExecutor")
    public TaskExecutor pipelineActivationExecutor() {
        return new SimpleAsyncTaskExecutor("pipeline-activate-");
    }
}
