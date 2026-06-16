---
doc_id: glossary:dlq
doc_type: glossary
title: DLQ (Dead Letter Queue)
tags: [kafka, dlq, dead-letter-queue]
source: synthetic
---

DLQ는 처리에 실패한 메시지를 정상 흐름과 분리해 보관하는 큐다. Kafka Connect에서는 변환 실패, serialization 오류, sink 쓰기 실패가 반복될 때 DLQ topic이 빠르게 증가할 수 있다. 운영자는 DLQ 증가율을 connector task 상태, 최근 배포, schema 변경 이력과 함께 확인해야 한다. DLQ가 있다고 해서 장애가 자동으로 해소된 것은 아니며, 실패 레코드의 원인 분류와 재처리 가능성 판단이 뒤따라야 한다. Bifrost에서는 DLQ 급증을 데이터 손실 위험 신호로 보고 RCA evidence에 포함한다.
