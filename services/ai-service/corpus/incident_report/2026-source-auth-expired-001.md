---
doc_id: incident_report:2026-source-auth-expired-001
doc_type: incident_report
title: Postgres source credential expired
tags: [SOURCE_AUTH_EXPIRED, rotate_credentials, source]
source: synthetic
---

## 증상
customer-orders-source connector가 source database 연결 단계에서 반복 실패했고 신규 주문 CDC 이벤트가 유입되지 않았다. Connector task는 재시작 직후 FAILED로 돌아갔다.

## 근거(evidence)
Worker log에는 `password authentication failed for user cdc_reader`가 반복되었고 secret rotation 이벤트가 장애 20분 전에 기록되었다. Network reachability check는 성공했고 source port 연결 timeout은 없었다.

## 근본원인
root_cause_id=SOURCE_AUTH_EXPIRED. Source credential 또는 token이 만료되었거나 회전 후 connector secret과 불일치한 상황이다.

## 조치
`rotate_credentials` 도구로 credential owner에게 갱신 요청을 생성하고, secret 원문 조회 없이 rotation evidence를 첨부했다. 반복 실패로 인한 noise를 줄이기 위해 필요 시 `pause_connector`를 승인 대상으로 제안했다.

## 복구
Credential owner가 새 secret을 반영한 뒤 connector를 재시작했고 source snapshot read가 정상화되었다. 이후 authentication failure 로그가 사라지고 CDC offset이 다시 전진했다.
