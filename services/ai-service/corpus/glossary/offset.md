---
doc_id: glossary:offset
doc_type: glossary
title: Kafka Offset
tags: [kafka, offset, consumer]
source: synthetic
---

Offset은 partition 안에서 메시지의 위치를 나타내는 단조 증가 값이다. Consumer는 처리 완료 지점을 offset commit으로 기록하고, 장애 후 이 값을 기준으로 재시작한다. Offset commit이 멈추면 실제 처리가 느린 것인지 commit path만 실패한 것인지 분리해서 봐야 한다. 운영자는 latest offset, committed offset, processed record count, retry log를 같은 시간대에서 비교한다. Bifrost는 offset progression 정체를 consumer lag evidence로 기록한다.
