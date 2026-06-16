---
doc_id: glossary:kafka-connect-converter
doc_type: glossary
title: Kafka Connect Converter
tags: [kafka-connect, converter, schema]
source: synthetic
---

Kafka Connect converter는 Connect 내부 데이터와 Kafka record bytes 사이의 직렬화 형식을 변환한다. AvroConverter, JsonConverter, StringConverter 설정이 topic payload와 맞지 않으면 deserialization 오류나 schema id 조회 실패가 발생한다. Converter 설정 변경은 connector task restart 이후에야 문제로 드러나는 경우가 많다. 운영자는 key.converter, value.converter, schemas.enable, schema registry URL을 확인해야 한다. Bifrost는 converter 오류를 SCHEMA_MISMATCH 또는 CONNECTOR_TASK_FAILED evidence로 연결한다.
