---
doc_id: glossary:schema-registry
doc_type: glossary
title: Schema Registry
tags: [schema-registry, schema, kafka]
source: synthetic
---

Schema Registry는 Avro, Protobuf, JSON Schema 같은 메시지 schema와 compatibility 정책을 관리한다. Producer가 새 schema를 등록하거나 consumer가 schema id를 해석할 때 registry 접근과 compatibility 검사가 필요하다. Schema 등록 실패나 incompatible 변경은 connector task failure와 DLQ 증가로 이어질 수 있다. 운영자는 subject, version, compatibility mode, 최근 schema 변경자를 확인해야 한다. Bifrost는 schema registry evidence를 SCHEMA_MISMATCH 판단의 핵심 근거로 사용한다.
