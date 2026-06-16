---
doc_id: glossary:kafka-connect-task
doc_type: glossary
title: Kafka Connect Task
tags: [kafka-connect, task, connector]
source: synthetic
---

Kafka Connect task는 connector가 실제 데이터를 읽거나 쓰는 실행 단위다. Connector 상태가 RUNNING이어도 일부 task가 FAILED이면 해당 partition이나 table 흐름은 중단될 수 있다. 운영자는 task trace, worker log, connector config, 최근 rebalance 여부를 함께 확인해야 한다. Task 재시작은 transient 오류에는 효과적이지만, schema mismatch나 credential 만료 같은 구조적 원인에는 반복 실패를 만들 수 있다. Bifrost는 connector와 task 상태를 분리해 evidence로 기록한다.
