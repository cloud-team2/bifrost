---
doc_id: incident_report:2026-source-auth-expired-002
doc_type: incident_report
title: OAuth token expired for SaaS source connector
tags: [SOURCE_AUTH_EXPIRED, rotate_credentials, oauth]
source: synthetic
---

## 증상
crm-source connector가 HTTP 401 응답을 받으며 API polling을 중단했다. Connector status는 FAILED와 RUNNING을 반복했고 source freshness가 25분 이상 지연되었다.

## 근거(evidence)
Task trace에는 `invalid_token`과 `token expired` 응답이 포함되었고, 같은 시간대 SaaS API status page에는 장애가 없었다. DNS와 TLS handshake metric은 정상이며 request latency 증가도 없었다.

## 근본원인
root_cause_id=SOURCE_AUTH_EXPIRED. Source SaaS API access token이 만료되어 connector가 인증에 실패한 사례다.

## 조치
`rotate_credentials` 도구로 OAuth credential 재발급 절차를 시작하고 token scope 변경 여부를 확인했다. Agent는 secret 값을 직접 읽지 않고 만료 evidence와 영향 범위만 owner에게 전달했다.

## 복구
새 token 적용 후 connector polling이 재개되고 freshness delay가 정상 범위로 돌아왔다. 재발 방지를 위해 token expiry 알림과 사전 rotation runbook을 추가했다.
