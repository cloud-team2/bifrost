package com.bifrost.ops.streaming;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 워크스페이스 scope SSE 발행기(#70, monitoring.md §7).
 *
 * <p>구독자({@link SseEmitter})를 {@code wsId}별로 보관하고, 파이프라인 상태/connector 상태 변경 시
 * 해당 워크스페이스 구독자에게만 전송한다(다른 워크스페이스로 누출 없음). 전송 실패한 emitter는
 * 즉시 정리한다.
 */
@Component
public class SsePublisher {

    private static final Logger log = LoggerFactory.getLogger(SsePublisher.class);
    private static final long TIMEOUT_MS = 30 * 60 * 1000L; // 30분

    private final Map<UUID, List<SseEmitter>> emittersByWorkspace = new ConcurrentHashMap<>();

    /** 워크스페이스 구독 등록. 완료/타임아웃/오류 시 자동 정리. */
    public SseEmitter subscribe(UUID wsId) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        List<SseEmitter> list = emittersByWorkspace.computeIfAbsent(wsId, k -> new CopyOnWriteArrayList<>());
        list.add(emitter);
        emitter.onCompletion(() -> remove(wsId, emitter));
        emitter.onTimeout(() -> remove(wsId, emitter));
        emitter.onError(e -> remove(wsId, emitter));
        try {
            emitter.send(SseEmitter.event().name("connected").data(Map.of("wsId", wsId.toString())));
        } catch (IOException e) {
            remove(wsId, emitter);
        }
        return emitter;
    }

    /**
     * idle SSE 연결이 nginx ingress read-timeout(~60s)/버퍼링에 끊기지 않도록 주기 heartbeat(:ping, #630).
     * 이벤트가 한동안 없어도 연결을 살아있게 유지한다. 전송 실패한 emitter는 정리.
     */
    @Scheduled(fixedRate = 20_000)
    public void heartbeat() {
        emittersByWorkspace.forEach((wsId, list) -> {
            for (SseEmitter emitter : list) {
                try {
                    emitter.send(SseEmitter.event().comment("ping"));
                } catch (IOException | IllegalStateException e) {
                    remove(wsId, emitter);
                }
            }
        });
    }

    /** 파이프라인 상태 전이 알림. */
    public void pipelineStatusChanged(UUID wsId, UUID pipelineId, String status) {
        publish(wsId, "pipeline_status_changed",
                Map.of("pipelineId", pipelineId.toString(), "status", status));
    }

    /** connector 상태 변경 알림(상세 토글). */
    public void connectorStateChanged(UUID wsId, String connectorName, String state) {
        publish(wsId, "connector_state_changed",
                Map.of("connectorName", connectorName, "state", state));
    }

    /** 인시던트 생성·업데이트 알림(S2). */
    public void incidentEvent(UUID wsId, String eventName, Object payload) {
        publish(wsId, eventName, payload);
    }

    private void publish(UUID wsId, String eventName, Object payload) {
        List<SseEmitter> list = emittersByWorkspace.get(wsId);
        if (list == null || list.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(payload));
            } catch (IOException | IllegalStateException e) {
                remove(wsId, emitter);
            }
        }
        log.debug("SSE publish: ws={}, event={}", wsId, eventName);
    }

    private void remove(UUID wsId, SseEmitter emitter) {
        List<SseEmitter> list = emittersByWorkspace.get(wsId);
        if (list != null) {
            list.remove(emitter);
        }
    }
}
