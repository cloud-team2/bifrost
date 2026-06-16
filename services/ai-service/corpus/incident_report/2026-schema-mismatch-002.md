---
doc_id: incident_report:2026-schema-mismatch-002
doc_type: incident_report
title: Kafka Connect converter misconfigured
tags: [SCHEMA_MISMATCH, collect_schema_changes, converter]
source: synthetic
---

## 증상
customer-profile connector 재배포 직후 모든 task가 JSON 변환 오류를 내며 실패했다. Topic payload는 Avro로 유지되었지만 connector config의 value.converter가 JsonConverter로 바뀌어 있었다.

## 근거(evidence)
Task trace에는 `JsonParseException`과 schema id byte 해석 실패가 남았고, 배포 diff에서 `value.converter` 변경이 확인되었다. Schema Registry에는 같은 시간대 incompatible schema 등록이 없었다.

## 근본원인
root_cause_id=SCHEMA_MISMATCH. Kafka Connect converter 설정이 실제 topic serialization format과 맞지 않아 메시지 변환에 실패한 상황이다.

## 조치
`collect_schema_changes` 도구로 converter config diff와 schema registry subject 상태를 수집하고, config rollback을 변경관리 대상으로 제안했다. 반복 실패를 막기 위해 `pause_connector`를 임시 조치로 사용했다.

## 복구
Converter 설정을 AvroConverter로 되돌린 뒤 task가 RUNNING 상태로 복구되었다. DLQ에 쌓인 레코드는 동일 offset 구간을 기준으로 재처리 검증을 수행했다.
