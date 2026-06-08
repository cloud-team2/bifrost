package com.bifrost.ops.pipeline.kafka;

import org.apache.kafka.common.TopicPartition;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 토픽별 최근 2개 offset 스냅샷을 보관하는 인메모리 스토어(#126).
 *
 * <p>스냅샷은 {@link OffsetSnapshotCollector}가 주기적으로 기록하고, {@link RateResult}는
 * 두 스냅샷의 delta / elapsed_sec 으로 계산한다. API 스레드는 스토어를 읽기만 하며
 * AdminClient를 직접 호출하지 않는다.
 */
@Component
public class OffsetSnapshotStore {

    // topic → [older, newer]. OffsetSnapshot[] 참조 자체를 compute로 원자적으로 교체.
    private final ConcurrentHashMap<String, OffsetSnapshot[]> store = new ConcurrentHashMap<>();

    public void record(String topic, OffsetSnapshot snapshot) {
        store.compute(topic, (k, pair) -> {
            if (pair == null) return new OffsetSnapshot[]{null, snapshot};
            return new OffsetSnapshot[]{pair[1], snapshot};
        });
    }

    public RateResult getRates(String topic) {
        OffsetSnapshot[] pair = store.get(topic);
        if (pair == null || pair[0] == null || pair[1] == null) return RateResult.EMPTY;
        return calculate(pair[0], pair[1]);
    }

    private RateResult calculate(OffsetSnapshot s1, OffsetSnapshot s2) {
        double elapsed = Duration.between(s1.collectedAt(), s2.collectedAt()).toMillis() / 1000.0;
        if (elapsed <= 0) return RateResult.EMPTY;

        // produce rate: end offset 증가량 / 초
        long produced = 0L;
        for (Map.Entry<TopicPartition, Long> e : s2.endOffsets().entrySet()) {
            long e1 = s1.endOffsets().getOrDefault(e.getKey(), e.getValue());
            produced += Math.max(0, e.getValue() - e1);
        }

        // consume rate: committed offset 증가량 / 초
        long consumed = 0L;
        for (Map.Entry<TopicPartition, Long> e : s2.committedOffsets().entrySet()) {
            long c1 = s1.committedOffsets().getOrDefault(e.getKey(), e.getValue());
            consumed += Math.max(0, e.getValue() - c1);
        }

        // 현재 lag: end - committed (최신 스냅샷 기준)
        long lag = 0L;
        for (Map.Entry<TopicPartition, Long> e : s2.committedOffsets().entrySet()) {
            long end = s2.endOffsets().getOrDefault(e.getKey(), 0L);
            lag += Math.max(0, end - e.getValue());
        }

        return new RateResult(produced / elapsed, consumed / elapsed, lag);
    }
}
