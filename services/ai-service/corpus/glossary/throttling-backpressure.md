---
doc_id: glossary:throttling-backpressure
doc_type: glossary
title: Throttling and Backpressure
tags: [kafka, throttling, backpressure]
source: synthetic
---

Throttling은 과도한 요청을 제한하는 제어이고 backpressure는 downstream 처리 지연이 upstream으로 전달되는 현상이다. Sink DB가 느려지면 Kafka Connect sink task의 retry와 batch 처리 시간이 늘고 consumer lag가 증가할 수 있다. Broker quota나 client throttle도 produce/consume latency를 키우는 원인이 된다. 운영자는 throttle time, retry backoff, sink latency, connector batch size를 함께 확인한다. Bifrost는 backpressure를 단순 consumer 부족과 구분해 조치 위험도를 낮춘다.
