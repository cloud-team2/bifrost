---
doc_id: incident_report:2026-sink-auth-expired-001
doc_type: incident_report
title: Warehouse sink credential expired
tags: [SINK_AUTH_EXPIRED, rotate_credentials, sink]
source: synthetic
---

## 증상
analytics-warehouse-sink connector가 sink write 단계에서 인증 실패를 반환하며 batch flush를 완료하지 못했다. Source read와 Kafka topic ingress는 정상이나 sink 적재 freshness가 지연되었다.

## 근거(evidence)
Task trace에는 `SQLInvalidAuthorizationSpecException`이 반복되었고 warehouse audit log에는 connector service account token expiry가 기록되었다. Network latency와 DB connection timeout metric은 정상 범위였다.

## 근본원인
root_cause_id=SINK_AUTH_EXPIRED. Sink credential 또는 token이 만료되어 Kafka Connect sink task가 write 권한을 얻지 못한 사례다.

## 조치
`rotate_credentials` 도구로 sink owner에게 credential 갱신 요청을 만들고, secret 원문은 조회하지 않았다. 데이터 중복 위험을 줄이기 위해 connector offset과 flush 실패 batch 범위를 함께 기록했다.

## 복구
새 credential이 배포된 뒤 connector task가 batch flush를 재개했고 sink freshness가 정상화되었다. 복구 후 warehouse audit log에서 authorization failure가 더 이상 발생하지 않았다.
