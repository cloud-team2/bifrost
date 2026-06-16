---
doc_id: glossary:consumer-lag
doc_type: glossary
title: Consumer Lag
tags: [kafka, consumer, lag]
source: synthetic
---

Consumer lag는 Kafka topic의 최신 offset과 consumer group이 커밋한 offset 사이의 차이다. Lag가 일시적으로 증가한 뒤 줄어들면 정상적인 burst 처리일 수 있지만, 계속 증가하면 처리량 부족이나 downstream 지연을 의심해야 한다. 운영자는 partition별 lag, offset progression, consumer replica 수, broker throughput을 함께 본다. 특정 partition만 밀리면 partition skew나 hot key 가능성이 높다. Bifrost에서는 lag p95와 지속 시간을 기준으로 incident 후보를 만든다.
