---
doc_id: incident_report:2026-connector-task-failed-002
doc_type: incident_report
title: Source connector task failed on malformed CDC event
tags: [CONNECTOR_TASK_FAILED, pause_connector, kafka-connect]
source: synthetic
---

## 증상
inventory-source connector의 task-0이 FAILED가 되었고 CDC 이벤트 유입이 한 table에서만 중단되었다. DLQ topic에는 같은 table의 update 이벤트가 짧은 시간에 집중적으로 쌓였다.

## 근거(evidence)
Task trace에는 `DataException: malformed envelope for table inventory_adjustment`가 남았고, source offset은 실패 직전 LSN에서 더 이상 전진하지 않았다. 최근 connector image 배포는 없었고 worker rebalance 이벤트도 같은 시간대에 없었다.

## 근본원인
root_cause_id=CONNECTOR_TASK_FAILED. 특정 CDC 이벤트 처리 중 connector task가 FAILED 상태로 전환되어 source read가 멈춘 상황이다.

## 조치
`pause_connector` 도구로 반복 실패 확산을 막고 실패 payload와 task trace를 수집했다. 원인 이벤트 격리 후 `restart_connector`를 승인 요청 대상으로 제안했다.

## 복구
문제 레코드를 DLQ 기준으로 격리한 뒤 connector를 재시작했고 source offset이 다시 전진했다. 복구 후 DLQ 증가율과 task 상태를 30분 동안 모니터링했다.
