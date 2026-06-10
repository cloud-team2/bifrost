package com.bifrost.ops.global.config;

import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.aop.ObservedAspect;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 관측(Observability) 설정 — {@code @Observed}가 Observation/span으로 동작하도록 ObservedAspect를
 * 빈으로 등록한다(#366). 생성된 span은 OTLP로 Tempo에 송신된다(설정: application.yml management.otlp.tracing).
 *
 * <p>HTTP 요청은 Spring Boot가 자동 계측하고, 파이프라인 핵심 작업(생성/상태전이/프로비저닝/폴링)은
 * 각 서비스 메서드의 {@code @Observed}로 별도 span을 남긴다.
 */
@Configuration
public class ObservabilityConfig {

    @Bean
    ObservedAspect observedAspect(ObservationRegistry observationRegistry) {
        return new ObservedAspect(observationRegistry);
    }
}
