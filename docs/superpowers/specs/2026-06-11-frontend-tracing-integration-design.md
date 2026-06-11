# 프론트엔드 추적(trace) 연동 — 설계

- 날짜: 2026-06-11
- 상태: 승인됨(브레인스토밍) → 구현 계획 대기
- 관련: #366(앱 계측) · #371(데이터플레인 SMT) · #373(query_traces Tempo 교체) · 후속 #370/#372

## 1. 배경 / 문제

추적 인프라는 깔려 있다 — 앱 span(#366), 데이터플레인 span(#371, Debezium `ActivateTracingSpan` SMT) → Tempo, 그리고 `query_traces`가 Tempo 분산 trace 요약을 반환(#373). 하지만 **사람이 보는 화면(프론트)에는 추적이 노출되지 않는다**. 변경 이벤트가 source→topic→sink로 흐르며 어디서 지연/실패했는지를 사용자·RCA가 직접 보게 한다.

데이터: `query_traces` = 파이프라인별 분산 trace 요약 `{traceId, pipelineId, status, durationMs, spans:[{name, service, durationMs, status, error}]}`.

## 2. 목표 / 비목표

목표
- 사용자가 파이프라인의 분산 trace(어디서 지연/실패)를 **직접 조회**
- RCA 에이전트가 가져온 trace를 **진단 근거로 표시** + 상세로 연결
- CDC는 풀(source→topic→sink), EDA는 가능한 구간(source→topic) + 고객 확장 경로 제공

비목표(후속/별도)
- 고객 consumer span을 플랫폼 Tempo로 수집(per-tenant OTLP ingest) — 멀티테넌트 보안·인프라 부담 큼
- tail-sampling(#370), FastAPI 자체 계측(#372)

## 3. 핵심 결정 (브레인스토밍)

| 결정 | 선택 | 근거 |
| --- | --- | --- |
| 배치 | **둘 다**: 파이프라인 상세 "추적" 탭 + AgentRunPanel | 셀프 조회 + RCA 근거, 공유 컴포넌트 재사용 |
| 에이전트 깊이 | **컴팩트 카드 + 링크** | evidence는 redacted 요약만 옴(설계 원칙). 풀 waterfall은 탭으로 |
| 링크 동작 | **traceId 딥링크** | 에이전트가 본 그 trace를 정확히 표시(일관성). "trace by id" 경로 1개 추가 |
| CDC | **풀 waterfall** | source+sink 모두 플랫폼 관리 → 전 구간 한 trace |
| EDA | **source→topic + traceId + 계측 스니펫** | sink 없음(consumer=고객 앱). 우리 UI는 source→topic까지 + 공유 traceId. 고객은 스니펫(OTel) 계측 시 자기 관측도구에서 이어봄 |

**EDA 현실**: `ActivateTracingSpan`이 traceparent를 Kafka 헤더에 주입하지만, 외부 고객 consumer span은 고객 자기 Tempo로 가지 in-cluster Tempo로 오지 않는다(멀티테넌트 보안). 그래서 플랫폼 UI는 source→topic + traceId만 그리고, 고객이 traceId로 자기 쪽에서 나머지를 본다.

## 4. 아키텍처

### 4.1 Spring (operations-backend)
- `PipelineController`
  - `GET /api/v1/workspaces/{wsId}/pipelines/{id}/trace` — 최근 trace 요약(탭 기본)
  - `GET …/pipelines/{id}/trace?traceId=<id>` — 특정 trace(딥링크)
- `PipelineService.trace(wsId, principal, id, traceId?)` — `accessGuard.requireAccess` + 파이프라인 source 커넥터·topic 해석 → TraceQuery, `TraceSummaryResult` 반환
- `TempoClient.traceById(traceId)` — `/api/traces/{id}` fetch+parse(기존 `recentTrace`의 fetch 경로 재사용)
- `TraceQuery.queryById(connectorName, traceId)` — 기존 `query(최근)`와 병렬. 비활성/미발견 → stub
- 반환 span: CDC = source→topic→sink, EDA = source→topic(있는 span 그대로)

### 4.2 FastAPI (ai-service)
- get_traces evidence 방출 시 payload에 `trace_id`·`pipeline_id` 포함 → SSE `evidence_collected` 이벤트로 전달(딥링크 식별자)

### 4.3 프론트엔드
- **`TraceWaterfall`**(공유 컴포넌트): `TraceSummary`(= Spring `TraceSummaryResult` 타입) 입력. `mode: 'full' | 'compact'`. 에러 span 강조, stub/빈 상태("추적 비활성/데이터 없음")
- **"추적" 탭**(`TraceTab`):
  - CDC 탭 목록에 "추적" 추가 → 풀 waterfall
  - EDA 탭 목록에 "추적" 추가 → source→topic + traceId + "consumer는 고객 앱(스니펫 계측 시 같은 traceId로 이어봄)" 안내
  - `/trace`(최근) 또는 앱 상태의 `traceId`로 조회. 새로고침(최근) 버튼
- **AgentRunPanel**: `evidenceType==='trace'` → `TraceWaterfall compact` + "추적 탭에서 상세 보기" → 앱 상태 전환(`view='pipeline-detail'` + pipeline + `tab='추적'` + `traceId`). 네비는 URL이 아니라 상태(view) 기반

### 4.4 EDA Connection Guide 스니펫
- 언어별 consumer 스니펫(Java/JS/Python)에 **Kafka 헤더 traceparent 읽기 + OTel 계측 예시** 추가

## 5. 데이터 흐름

```
[탭 기본]  TraceTab → GET …/trace                    → TraceQuery.query(최근)    → TraceWaterfall(full)
[딥링크]   AgentPanel(카드 클릭) → view 전환(traceId)  → TraceTab → GET …/trace?traceId=  → TraceQuery.queryById → TraceWaterfall(full)
[에이전트] get_traces → evidence(trace_id,pipeline_id) → SSE evidence_collected → TraceWaterfall(compact) 카드
```

## 6. 에러 / 빈 상태
- `tempo.enabled=false`·trace 미발견·Tempo 실패 → `TraceSummaryResult.stub`(traceId=null + note) → 탭은 빈 상태 안내, 카드는 요약만. 항상 200/파싱 안전(#391 정책 일관)

## 7. 테스트
- Spring: trace 엔드포인트(최근/by-id) + 접근가드, `TraceQuery.queryById`, `TempoClient.traceById` 파싱
- FastAPI: trace evidence가 `trace_id`/`pipeline_id`를 포함
- 프론트: `TraceWaterfall`(full/compact·에러·빈상태), `TraceTab`(CDC/EDA 분기), AgentRunPanel trace 카드·네비

## 8. 구현 분할 (3 PR, issue-first)
1. **Spring trace 엔드포인트 + 프론트 TraceTab + TraceWaterfall**(CDC 풀 + EDA source→topic) — 셀프 조회, 가장 자립적
2. **FastAPI evidence 보강 + AgentRunPanel 카드·딥링크** — RCA 근거 연결
3. **EDA Connection Guide 스니펫(traceparent/OTel)** — 작은 독립 작업

## 9. 범위 밖 / 후속
- 고객 consumer span 수집(per-tenant OTLP ingest) — end-to-end 완성, 큰 보안·인프라 과제
- #370 tail-sampling, #372 FastAPI 자체 OTel 계측
