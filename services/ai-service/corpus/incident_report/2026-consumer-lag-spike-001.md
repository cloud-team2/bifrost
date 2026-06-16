---
doc_id: incident_report:2026-consumer-lag-spike-001
doc_type: incident_report
title: Consumer lag spike from downstream write latency
tags: [CONSUMER_LAG_SPIKE, scale_consumers, consumer]
source: synthetic
---

## 증상
billing-events consumer group의 total lag가 15분 동안 12만 건에서 180만 건으로 증가했다. Producer ingress는 평소보다 약간 높았지만 broker produce latency는 정상 범위였다.

## 근거(evidence)
`get_consumer_lag` 결과 모든 partition lag가 고르게 증가했고 consumer pod 로그에는 sink API write latency가 p95 8초로 증가한 기록이 있었다. CPU 사용률은 여유가 있었지만 처리 완료 offset commit 간격이 길어졌다.

## 근본원인
root_cause_id=CONSUMER_LAG_SPIKE. Consumer 처리량이 유입량보다 낮아 lag가 빠르게 증가한 사례이며 주된 병목은 downstream write latency였다.

## 조치
`scale_consumers` 도구로 consumer replica를 4개에서 8개로 늘리는 조치를 제안하고, 동시에 sink latency owner에게 escalation했다. Read-only 단계에서는 `get_consumer_lag`로 partition별 lag를 확인했다.

## 복구
Replica 증설 후 처리량이 ingress를 상회했고 lag가 40분 동안 점진적으로 해소되었다. Sink API latency가 정상화된 뒤 replica 수를 표준 운영값으로 되돌리는 후속 작업을 남겼다.
