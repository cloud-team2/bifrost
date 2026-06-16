---
doc_id: incident_report:2026-source-network-reachability-002
doc_type: incident_report
title: Intermittent packet loss to source API
tags: [SOURCE_NETWORK_REACHABILITY, network_diagnostics, packet-loss]
source: synthetic
---

## 증상
shipment-source connector가 간헐적으로 read timeout을 내며 source freshness가 들쭉날쭉해졌다. 장애는 특정 availability zone의 worker에서 더 자주 발생했다.

## 근거(evidence)
Worker log에는 `Read timed out`이 반복되었고 `network_diagnostics` 결과 해당 AZ 경로에서 packet loss가 18%까지 상승했다. Source API 인증은 성공했고 HTTP 401/403 응답은 관측되지 않았다.

## 근본원인
root_cause_id=SOURCE_NETWORK_REACHABILITY. Source API까지의 네트워크 reachability가 불안정해 connector read가 지연된 사례다.

## 조치
`network_diagnostics` 도구 결과를 첨부해 platform network owner에게 escalation하고, 영향 connector를 안정적인 worker pool로 옮기는 운영 조치를 제안했다. Credential rotation이나 connector config 변경은 원인과 맞지 않아 제외했다.

## 복구
문제 AZ 경로가 우회된 뒤 timeout 비율이 정상화되었다. 이후 source freshness p95와 connector retry count가 기준치 아래로 유지되었다.
