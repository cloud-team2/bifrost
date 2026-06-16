---
doc_id: incident_report:2026-consumer-lag-spike-002
doc_type: incident_report
title: Lag spike concentrated on hot partition
tags: [CONSUMER_LAG_SPIKE, create_rebalance_proposal, partition-skew]
source: synthetic
---

## 증상
clickstream-enriched consumer group에서 total lag가 증가했지만 lag의 72%가 partition-17 하나에 집중되었다. 대시보드 평균 처리량은 정상처럼 보였으나 freshness SLA가 일부 고객 이벤트에서 깨졌다.

## 근거(evidence)
Partition별 metric에서 partition-17 ingress가 다른 partition 대비 9배 높았고 같은 partition을 담당한 consumer만 batch 처리 시간이 길었다. Broker resource metric은 정상이고 consumer group rebalance loop도 관측되지 않았다.

## 근본원인
root_cause_id=CONSUMER_LAG_SPIKE. Consumer lag spike가 발생했으며 직접 원인은 hot key로 인한 partition-skew였다.

## 조치
`create_rebalance_proposal` 도구로 partition placement 점검을 제안하고, consumer 증설만으로 해결되지 않는다는 내용을 RCA에 명시했다. 임시로 `scale_consumers`는 효과 제한을 표시한 보조 조치로만 남겼다.

## 복구
Hot key 고객 이벤트를 별도 처리 경로로 분리한 뒤 partition-17 lag가 감소했다. 복구 보고서에는 key 설계 개선과 partition 확장 검토를 예방 조치로 기록했다.
