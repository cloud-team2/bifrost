---
doc_id: glossary:broker
doc_type: glossary
title: Kafka Broker
tags: [kafka, broker, cluster]
source: synthetic
---

Broker는 Kafka cluster에서 topic partition을 저장하고 produce/consume 요청을 처리하는 서버다. Broker CPU, disk, network가 포화되면 consumer lag와 produce latency가 동시에 악화될 수 있다. 운영자는 broker별 leader partition 수, request queue, disk usage, under-replicated partition을 함께 확인한다. 특정 broker에 traffic이 몰리면 partition reassignment나 producer key 분포 점검이 필요하다. Bifrost는 broker pressure를 consumer 문제와 구분하기 위해 cluster metric을 evidence로 사용한다.
