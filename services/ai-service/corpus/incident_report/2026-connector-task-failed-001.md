---
doc_id: incident_report:2026-connector-task-failed-001
doc_type: incident_report
title: Connector task failed after transient sink timeout
tags: [CONNECTOR_TASK_FAILED, restart_connector, kafka-connect]
source: synthetic
---

## 증상
orders-sink connector의 task-2가 FAILED로 전환되고 해당 connector의 처리량이 0으로 떨어졌다. Connector 자체 상태는 RUNNING으로 표시되어 운영자가 첫 화면에서 부분 장애를 놓칠 수 있었다.

## 근거(evidence)
Kafka Connect REST API에서 task-2 trace에 `RetriableException: timeout while flushing batch`가 반복되었고, worker log에는 같은 시각 sink 응답 지연이 30초 이상 기록되었다. 다른 task는 RUNNING이며 같은 topic의 다른 partition offset은 정상 진행했다.

## 근본원인
root_cause_id=CONNECTOR_TASK_FAILED. 단일 Kafka Connect task가 실패 상태로 멈춰 connector 단위 데이터 이동이 부분 중단된 사례다.

## 조치
`restart_connector` 도구로 orders-sink connector를 재시작하고 task 상태를 다시 확인했다. 재시작 전에는 task trace와 connector config snapshot을 evidence로 보관했다.

## 복구
재시작 후 task-2가 RUNNING으로 돌아왔고 10분 안에 해당 partition lag가 정상 범위로 감소했다. 이후 동일 trace가 재발하지 않아 일시적 sink timeout에 의한 task failure로 종료했다.
