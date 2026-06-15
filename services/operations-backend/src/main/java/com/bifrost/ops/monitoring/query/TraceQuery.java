package com.bifrost.ops.monitoring.query;

import com.bifrost.ops.adapters.tempo.TempoClient;
import com.bifrost.ops.adapters.tempo.TempoClient.TempoTrace;
import com.bifrost.ops.internalops.dto.TraceSummaryResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

import java.time.Instant;
import java.util.Optional;

/**
 * query_traces Tempo 조회 서비스(#373, ai-service {@code get_traces} tool 대응).
 *
 * <p>{@link ObservabilityMetricsQuery}와 동일 정책: {@code tempo.enabled=false}(기본)면 Tempo를
 * 호출하지 않고 well-formed stub을 반환하고, true면 변경 이벤트 trace(source→topic→sink)를 조회하되
 * 미발견·접속 실패 시 stub으로 폴백한다(항상 200 + 파싱 안전).
 *
 * <p>커넥터 → trace 연결은 데이터플레인 span의 topic(messaging.destination)을 join 키로 쓴다.
 * topic을 못 구하면 service.name=platform-connect로 범위만 좁혀 최근 trace를 가져온다(best-effort).
 */
@Service
public class TraceQuery {

    private static final Logger log = LoggerFactory.getLogger(TraceQuery.class);
    private static final String DATAPLANE_SERVICE = "platform-connect";
    // (#713) Tempo /api/search는 결과를 oldest-first로 limit만큼 절단해 반환한다. 큰 창에 trace가 많으면
    // (중·고볼륨) limit개가 전부 창 시작점(옛) trace라 최신을 골라도 옛 것이다. 활성 파이프라인은 짧은
    // 창에서 최신을 잡고, 비면(idle) 긴 창으로 폴백해 마지막 trace라도 보여준다.
    private static final long RECENT_LOOKBACK_SEC = 300L;  // 5분 — 활성 파이프라인 최신(창 슬라이드)
    private static final long FULL_LOOKBACK_SEC = 3600L;   // 1h — idle 폴백

    private final boolean enabled;
    private final TempoClient client;

    public TraceQuery(@Value("${tempo.enabled:false}") boolean enabled, TempoClient client) {
        this.enabled = enabled;
        this.client = client;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 커넥터의 최근 분산 trace 요약을 반환한다. 항상 non-null.
     *
     * @param connectorName source/sink 커넥터 이름(파이프라인 식별)
     * @param topic         커넥터의 데이터 토픽(연결 정확도↑). 없으면 null — service 범위로 폴백.
     */
    public TraceSummaryResult query(String connectorName, String topic) {
        String pipelineId = pipelineIdOf(connectorName);
        if (!enabled) {
            return TraceSummaryResult.stub(pipelineId, "Tempo 비활성화 — stub 응답");
        }
        String traceql = traceqlFor(topic);
        long end = Instant.now().getEpochSecond();
        try {
            Optional<TempoTrace> trace = client.recentTrace(traceql, end - RECENT_LOOKBACK_SEC, end);
            if (trace.isEmpty()) {
                trace = client.recentTrace(traceql, end - FULL_LOOKBACK_SEC, end);
            }
            if (trace.isEmpty()) {
                return TraceSummaryResult.stub(pipelineId, "구간 내 trace 없음");
            }
            TempoTrace t = trace.get();
            return TraceSummaryResult.of(
                    t.traceId(), pipelineId, t.error() ? "error" : "ok", t.durationMs(), t.spans());
        } catch (RestClientException e) {
            log.debug("Tempo query_traces 실패(stub fallback): connector={} cause={}", connectorName, e.getMessage());
            return TraceSummaryResult.stub(pipelineId, "Tempo 조회 실패 — stub 응답");
        }
    }

    /**
     * 특정 traceId의 trace 요약. 비활성/미발견/실패 시 stub.
     *
     * @param connectorName source/sink 커넥터 이름(파이프라인 식별)
     * @param traceId       조회할 trace ID
     */
    public TraceSummaryResult queryById(String connectorName, String traceId) {
        String pipelineId = pipelineIdOf(connectorName);
        if (!enabled) {
            return TraceSummaryResult.stub(pipelineId, "Tempo 비활성화 — stub 응답");
        }
        try {
            Optional<TempoTrace> trace = client.traceById(traceId);
            if (trace.isEmpty()) {
                return TraceSummaryResult.stub(pipelineId, "trace 없음: " + traceId);
            }
            TempoTrace t = trace.get();
            return TraceSummaryResult.of(t.traceId(), pipelineId, t.error() ? "error" : "ok", t.durationMs(), t.spans());
        } catch (RestClientException e) {
            log.debug("Tempo queryById 실패(stub): traceId={} cause={}", traceId, e.getMessage());
            return TraceSummaryResult.stub(pipelineId, "Tempo 조회 실패 — stub 응답");
        }
    }

    /** topic이 있으면 데이터플레인 span을 topic으로 좁히고, 없으면 service.name으로만 범위를 잡는다. */
    private static String traceqlFor(String topic) {
        if (topic != null && !topic.isBlank()) {
            return "{ resource.service.name=\"" + DATAPLANE_SERVICE
                    + "\" && span.messaging.destination.name=\"" + topic + "\" }";
        }
        return "{ resource.service.name=\"" + DATAPLANE_SERVICE + "\" }";
    }

    /** {@code <uuid>-source}/{@code -sink} → {@code <uuid>}. 형식이 다르면 connectorName 그대로. */
    private static String pipelineIdOf(String connectorName) {
        if (connectorName == null) {
            return null;
        }
        if (connectorName.endsWith("-source")) {
            return connectorName.substring(0, connectorName.length() - "-source".length());
        }
        if (connectorName.endsWith("-sink")) {
            return connectorName.substring(0, connectorName.length() - "-sink".length());
        }
        return connectorName;
    }
}
