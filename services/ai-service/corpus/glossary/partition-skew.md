---
doc_id: glossary:partition-skew
doc_type: glossary
title: Partition Skew
tags: [kafka, partition, skew]
source: synthetic
---

Partition skew는 topic traffic이나 lag가 일부 partition에 과도하게 몰리는 상태다. Producer key 분포가 한쪽으로 치우치거나 특정 고객의 이벤트가 폭증하면 전체 consumer replica를 늘려도 병목 partition은 그대로 남을 수 있다. 운영자는 partition별 ingress, lag, consumer assignment, hot key 후보를 확인해야 한다. Skew가 심하면 key 설계, partition 수, downstream 처리 모델을 함께 검토해야 한다. Bifrost는 partition별 metric을 평균값보다 우선해 skew 신호를 찾는다.
