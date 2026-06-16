---
doc_id: glossary:consumer-group
doc_type: glossary
title: Consumer Group
tags: [kafka, consumer-group, offset]
source: synthetic
---

Consumer group은 여러 consumer instance가 topic partition을 나누어 처리하는 단위다. 같은 group.id를 가진 consumer들은 partition을 공유하며 각 partition은 보통 하나의 member가 처리한다. Member가 자주 교체되거나 heartbeat가 끊기면 rebalance가 반복되고 lag가 증가할 수 있다. 운영자는 group state, member count, assignment, committed offset을 함께 확인한다. Bifrost는 consumer group 상태를 lag spike와 rebalance loop를 구분하는 근거로 사용한다.
