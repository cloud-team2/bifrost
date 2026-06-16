---
doc_id: ops_doc:agent-rag-indexing-architecture
doc_type: ops_doc
title: Agent RAG Indexing Architecture Summary
tags: [ops, rag, architecture]
source: synthetic
---

# Agent RAG Indexing Architecture Summary

Bifrost Agent의 RAG 지식은 기본 runbook과 glossary, curated catalog, ops_doc, synthetic incident_report로 나뉜다. 각 문서는 `doc_id`, `doc_type`, `title`, `tags`, `source`를 frontmatter에 명시해 검색 결과가 agent trace에서 추적 가능하도록 한다. Seed loader는 문서를 직접 해석하지 않고 `index_document` API에 위임해 chunking, embedding, deterministic chunk id 생성을 기존 knowledge 계층과 동일하게 유지한다. 배포 Job은 `seed_all`을 호출해 built-in corpus와 파일 기반 corpus를 같은 version으로 적재할 수 있다. 운영자는 manifest count와 doc_type별 적재 결과를 비교해 누락된 문서를 빠르게 확인한다.
