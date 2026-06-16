---
doc_id: glossary:partition-rebalance
doc_type: glossary
title: Partition Rebalance
tags: [kafka, partition, rebalance]
source: synthetic
---

Partition rebalance는 partition ownership이나 placement가 재조정되는 과정이다. Consumer group rebalance는 member join/leave나 heartbeat 지연으로 발생하고, broker partition rebalance는 leader와 replica 배치를 바꿀 때 발생한다. 잦은 rebalance는 처리 중단 구간을 만들고 consumer lag를 급격히 키울 수 있다. 운영자는 rebalance 시작 시각, group membership 변화, broker assignment 변경 이력을 incident timeline에 맞춰 본다. Bifrost는 rebalance loop와 단발성 조정을 구분해 RCA 후보를 좁힌다.
