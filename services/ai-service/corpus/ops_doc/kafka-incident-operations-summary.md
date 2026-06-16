---
doc_id: ops_doc:kafka-incident-operations-summary
doc_type: ops_doc
title: Kafka Incident Operations Summary
tags: [ops, kafka, incident-response]
source: synthetic
---

# Kafka Incident Operations Summary

Kafka 장애 분석은 증상보다 evidence 경계를 먼저 정리해야 한다. Connector task failure는 task trace와 worker log가 핵심이고, consumer lag spike는 partition별 lag와 offset progression이 핵심이다. Source나 sink 인증 문제는 secret 원문을 보지 않고 인증 실패 로그, rotation event, owner escalation 기록으로 판단한다. Schema mismatch는 schema registry subject/version, converter config, DLQ payload class를 함께 확인해야 한다. Bifrost Agent는 조치 제안 시 root_cause_id와 허용된 tool을 함께 제시해 승인 정책과 실행 가능성을 분리한다.
