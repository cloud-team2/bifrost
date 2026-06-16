---
doc_id: glossary:isr
doc_type: glossary
title: ISR (In-Sync Replica)
tags: [kafka, replication, isr]
source: synthetic
---

ISR은 leader와 충분히 동기화된 replica 집합을 뜻한다. ISR이 줄어들면 broker 장애, network 지연, disk flush 지연 때문에 replica가 leader를 따라가지 못하고 있다는 신호일 수 있다. 운영자는 min.insync.replicas 설정, under-replicated partition, broker log를 함께 확인해야 한다. ISR 축소가 지속되면 produce 가용성과 durability 위험이 동시에 커진다. Bifrost는 ISR 변화를 broker resource pressure나 network reachability evidence로 연결한다.
