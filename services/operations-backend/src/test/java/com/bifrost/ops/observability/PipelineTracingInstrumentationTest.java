package com.bifrost.ops.observability;

import com.bifrost.ops.pipeline.service.PipelineService;
import io.micrometer.observation.aop.ObservedAspect;
import io.micrometer.observation.tck.TestObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import java.util.UUID;

import static io.micrometer.observation.tck.TestObservationRegistryAssert.assertThat;

/**
 * #366 파이프라인 앱 추적 — 핵심 작업 메서드가 Observation(span)으로 계측되는지 검증.
 *
 * <p>ObservedAspect를 직접 엮어 {@code @Observed} 어노테이션이 Observation을 생성하는지 단위 수준에서
 * 확인한다. 협력자는 모두 null이라 메서드 본문은 곧바로 실패하지만, ObservedAspect가 메서드 호출을
 * 감싸 Observation을 기록하므로 계측 여부 자체는 검증된다. (실제 Tempo 송신은 배포 환경 통합 검증.)
 */
class PipelineTracingInstrumentationTest {

    @Test
    void pipelineCreateEmitsObservation() {
        TestObservationRegistry registry = TestObservationRegistry.create();
        PipelineService target = new PipelineService(null, null, null, null, null, null, null, null, null, null);
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.addAspect(new ObservedAspect(registry));
        PipelineService proxied = factory.getProxy();

        try {
            proxied.create(UUID.randomUUID(), null, null);
        } catch (Throwable ignored) {
            // 협력자 null로 본문에서 예외 — Observation은 그대로 기록된다.
        }

        assertThat(registry).hasObservationWithNameEqualTo("pipeline.create");
    }
}
