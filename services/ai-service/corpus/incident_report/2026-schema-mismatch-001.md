---
doc_id: incident_report:2026-schema-mismatch-001
doc_type: incident_report
title: Avro compatibility break after producer deploy
tags: [SCHEMA_MISMATCH, rollback_schema, schema-registry]
source: synthetic
---

## 증상
orders-enriched sink connector에서 deserialization error가 발생하며 DLQ가 빠르게 증가했다. Consumer lag도 함께 증가했지만 broker와 sink DB metric은 정상 범위였다.

## 근거(evidence)
Schema Registry subject `orders-value`의 최신 version에서 required field가 추가되었고 compatibility check가 backward incompatible로 실패했다. Task trace에는 `Missing required field customerTier`가 반복되었다.

## 근본원인
root_cause_id=SCHEMA_MISMATCH. Producer schema 변경이 consumer와 connector의 기대 schema와 호환되지 않아 serialization/deserialization 실패가 발생했다.

## 조치
`rollback_schema` 도구로 schema 변경 rollback 절차를 제안하고, 확산 방지를 위해 `pause_connector`를 승인 대상으로 함께 제시했다. `collect_schema_changes`로 subject version과 변경자를 evidence에 포함했다.

## 복구
Schema rollback 후 신규 DLQ 유입이 중단되고 connector task가 정상 처리로 돌아왔다. 기존 DLQ 레코드는 호환 schema로 재처리 가능 여부를 확인한 뒤 별도 replay로 복구했다.
