# 프론트 추적 연동 PR 2 — FastAPI evidence 보강 + AgentRunPanel trace 카드·딥링크 (구현 계획)

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development로 task 단위 실행. 단계는 `- [ ]`.

**Goal:** RCA 에이전트가 수집한 trace를 AgentRunPanel에 컴팩트 카드로 보여주고, 클릭 시 그 파이프라인의 `Tracing` 탭으로 **traceId 딥링크**해 풀 waterfall(PR1)을 띄운다.

**Architecture:** FastAPI는 `get_traces` evidence 이벤트에 `trace_id`/`pipeline_id`를 실어 보낸다(이미 `ToolResult.raw_payload`에 Spring 응답이 있음). 프론트는 AppStore에 딥링크 상태(target tab + traceId)를 추가하고, AgentRunPanel 카드 → `openPipelineTrace(pipelineId, traceId)` → PipelineDetail이 `Tracing` 탭 + traceId로 진입 → TraceTab이 `api.pipelineTrace(wsId, id, traceId)` 조회.

**Tech Stack:** FastAPI/Python(ai-service, pytest), React+TS(frontend). 설계: `docs/superpowers/specs/2026-06-11-frontend-tracing-integration-design.md` §4.2/4.3. PR1(#511)에서 만든 `TraceWaterfall`/`pipelineTrace`/`Tracing` 탭 위에 빌드.

**디자인 충실도(필수):** AgentRunPanel의 새 카드/버튼은 기존 evidence 메시지·버튼 톤을 그대로 차용(Tailwind 클래스·색·간격). AI 티 금지. Task 4 전에 기존 evidence 렌더 정독.

---

## Task 1: FastAPI — trace evidence에 trace_id/pipeline_id 추가

**Files:**
- Modify: `services/ai-service/app/agents/retrieval.py`
- Test: `services/ai-service/tests/test_retrieval_trace_evidence.py` (신규)

**선행 정독:** `retrieval.py`의 `_raw_payload_for_store`(≈174)와 두 `EVIDENCE_COLLECTED` 방출부(≈118–131, ≈240–253). `ToolResult.raw_payload`는 Spring 전체 응답(`{ok, operation, result:{traceId, pipelineId, ...}, evidence, ...}`)이다.

- [ ] **Step 1: 헬퍼 + 실패 테스트** — 신규 `test_retrieval_trace_evidence.py`:

```python
from app.agents.retrieval import _trace_identifiers


class _R:
    def __init__(self, raw_payload):
        self.raw_payload = raw_payload


def test_extracts_trace_and_pipeline_id_from_raw_payload():
    r = _R({"operation": "query_traces", "result": {"traceId": "abc123", "pipelineId": "p1", "spans": []}})
    assert _trace_identifiers(r) == {"trace_id": "abc123", "pipeline_id": "p1"}


def test_missing_or_non_trace_yields_empty():
    assert _trace_identifiers(_R(None)) == {}
    assert _trace_identifiers(_R({"result": {"connector": "c"}})) == {}          # 비-trace 결과
    assert _trace_identifiers(_R({"result": {"traceId": None, "pipelineId": "p1"}})) == {"pipeline_id": "p1"}
```

- [ ] **Step 2: 실패 확인**

Run: `cd services/ai-service && .venv/bin/python -m pytest tests/test_retrieval_trace_evidence.py -q`
Expected: FAIL(`_trace_identifiers` 없음). (.venv 없으면 `.venv/bin/python` 대신 동작하는 인터프리터 사용 — repo의 ai-service venv.)

- [ ] **Step 3: 헬퍼 구현** — `retrieval.py`의 `_raw_payload_for_store` 근처(모듈 함수 영역)에 추가:

```python
def _trace_identifiers(result: Any) -> dict[str, str]:
    """get_traces 결과의 raw_payload에서 trace_id/pipeline_id를 추출(딥링크용). 비-trace/누락 시 빈 dict."""
    raw = getattr(result, "raw_payload", None)
    if not isinstance(raw, dict):
        return {}
    inner = raw.get("result")
    if not isinstance(inner, dict):
        return {}
    out: dict[str, str] = {}
    trace_id = inner.get("traceId") or inner.get("trace_id")
    pipeline_id = inner.get("pipelineId") or inner.get("pipeline_id")
    if isinstance(trace_id, str) and trace_id:
        out["trace_id"] = trace_id
    if isinstance(pipeline_id, str) and pipeline_id:
        out["pipeline_id"] = pipeline_id
    return out
```

- [ ] **Step 4: 방출부에 spread 추가** — 첫 번째 `EVIDENCE_COLLECTED` payload(≈126–131)에 `**_trace_identifiers(result)` 한 줄 추가(비-trace tool은 {} 반환이라 무해). 두 번째 방출부(knowledge, ≈246–252)는 trace와 무관하므로 변경하지 않는다.

```python
                payload={
                    "evidence_id": evidence.evidence_id,
                    "evidence_type": evidence.type.value,
                    "summary": summary[:80],
                    "redaction_status": evidence.redaction_status.value,
                    **_trace_identifiers(result),
                },
```

- [ ] **Step 5: 통과 + 전체 ai-service 테스트**

Run: `cd services/ai-service && .venv/bin/python -m pytest tests/test_retrieval_trace_evidence.py -q && .venv/bin/python -m pytest -q`
Expected: 신규 PASS, 전체 PASS(회귀 없음).

- [ ] **Step 6: 커밋** (NO Claude/AI attribution, author hwan only)
```bash
git add services/ai-service/app/agents/retrieval.py services/ai-service/tests/test_retrieval_trace_evidence.py
git commit -m "#498 [feat] get_traces evidence에 trace_id/pipeline_id 추가 — 딥링크 식별자"
```

---

## Task 2: 프론트 AppStore — 딥링크 상태 + openPipelineTrace

**Files:**
- Modify: `services/frontend/src/store/AppStore.tsx`

**선행 정독:** `Store` 인터페이스의 `selectedPipelineId`/`openPipeline`, 상태 선언부(`const [selectedPipelineId, setSelectedPipelineId] = useState(...)` ≈155), `openPipeline` 구현, 그리고 persist/복원 직렬화 부분(`view: s.view, selectedPipelineId: …` 류). 새 상태도 같은 방식으로 노출/복원한다.

- [ ] **Step 1: 상태 + 액션 추가**
  - 상태: `selectedTraceId: string | null`, `pipelineTab: string | null`(딥링크 대상 탭) 추가 — `selectedPipelineId`와 같은 위치에 `useState<string | null>(null)`로.
  - `Store` 인터페이스에 노출: `selectedTraceId: string | null`, `pipelineTab: string | null`, `openPipelineTrace: (pipelineId: string, traceId: string) => void`.
  - 구현(`openPipeline` 바로 옆):

```ts
const openPipelineTrace = (pipelineId: string, traceId: string) => {
  setSelectedPipelineId(pipelineId)
  setSelectedTraceId(traceId)
  setPipelineTab('Tracing')
  setView('pipeline-detail')   // 기존 openPipeline이 view를 어떻게 바꾸는지 보고 동일 방식 사용
}
```
  - 기존 `openPipeline`은 일반 진입이므로, 호출 시 `setSelectedTraceId(null); setPipelineTab(null)`로 딥링크 상태를 초기화하도록 한 줄씩 추가(스테일 딥링크 방지).
  - context value(`view, selectedPipelineId, …`)에 `selectedTraceId, pipelineTab, openPipelineTrace` 추가.
  - persist 직렬화에 `selectedTraceId`/`pipelineTab`을 포함하지 않아도 됨(세션 내 네비 상태). 기존 persist 목록을 건드리지 말 것.

- [ ] **Step 2: 타입체크**

Run: `cd services/frontend && npx tsc --noEmit`
Expected: 에러 없음

- [ ] **Step 3: 커밋**
```bash
git add services/frontend/src/store/AppStore.tsx
git commit -m "#498 [feat] AppStore: openPipelineTrace + 딥링크 상태(selectedTraceId/pipelineTab)"
```

---

## Task 3: 프론트 PipelineDetail — 딥링크 탭/traceId 반영

**Files:**
- Modify: `services/frontend/src/pages/dev/PipelineDetail.tsx`

- [ ] **Step 1: 초기 탭을 store의 pipelineTab으로**
  - `const [tab, setTab] = useState(tabs[0])`를, 진입 시 `app.pipelineTab`이 유효한 탭이면 그걸 초기값으로 쓰도록 변경:

```tsx
const [tab, setTab] = useState(() =>
  app.pipelineTab && tabs.includes(app.pipelineTab) ? app.pipelineTab : tabs[0],
)
```
  (`tabs`가 `useState` 초기화 시점에 정의돼 있어야 함 — 현재 `tabs`는 `tab` 선언 위에서 계산되므로 순서 OK. 아니면 `tabs` 계산을 `tab` 선언 위로 옮긴다.)

- [ ] **Step 2: TraceTab에 traceId 전달** — `TraceTab`이 store의 `selectedTraceId`를 읽어 `api.pipelineTrace(wsId, edge.id, traceId)`로 조회. PR1의 `TraceTab`을 수정:

```tsx
function TraceTab({ edge }: { edge: Edge }) {
  const app = useApp()
  const wsId = app.currentProject?.id
  const traceId = app.selectedTraceId ?? undefined
  const [trace, setTrace] = useState<TraceSummaryResponse | null>(null)
  const [error, setError] = useState(false)

  useEffect(() => {
    if (!wsId) return
    let cancelled = false
    setTrace(null)
    setError(false)
    api.pipelineTrace(wsId, edge.id, traceId)
      .then((t) => { if (!cancelled) setTrace(t) })
      .catch(() => { if (!cancelled) setError(true) })
    return () => { cancelled = true }
  }, [wsId, edge.id, traceId])
  // ... 이하 PR1과 동일(에러/로딩/EDA 노트/TraceWaterfall)
}
```
  (에러/로딩/렌더 부분은 PR1 그대로 둔다. `traceId`를 deps에 추가.)

- [ ] **Step 3: 타입체크 + vitest**

Run: `cd services/frontend && npx tsc --noEmit && npm run test`
Expected: 에러 없음, vitest 전체 PASS

- [ ] **Step 4: 커밋**
```bash
git add services/frontend/src/pages/dev/PipelineDetail.tsx
git commit -m "#498 [feat] PipelineDetail: 딥링크 탭/traceId 반영 — TraceTab 특정 trace 조회"
```

---

## Task 4: 프론트 AgentRunPanel — trace evidence 컴팩트 카드 + 딥링크

**Files:**
- Modify: `services/frontend/src/pages/ai/AgentRunPanel.tsx`

**선행 정독(필수, 디자인 충실도):** AgentRunPanel에서 `kind: 'evidence'` 메시지가 렌더되는 JSX를 찾아 읽는다(EvidenceMsg 인터페이스 + 렌더 매핑). 기존 evidence 카드의 컨테이너/텍스트 Tailwind 클래스와 버튼/링크 스타일을 그대로 차용한다. 또한 `evidence_collected` 이벤트 파싱부(≈439–447, `payloadString(event,'evidence_id')` 등)에 `trace_id`/`pipeline_id`를 추가로 파싱한다.

- [ ] **Step 1: 이벤트 파싱에 trace_id/pipeline_id 추가** — `evidence_collected` 처리부에서 `EvidenceMsg`에 `traceId`/`pipelineId`를 실어둔다:

```tsx
// evidence_collected 핸들러 (기존 evidenceId/evidenceType/summary 옆)
traceId: payloadString(event, 'trace_id'),
pipelineId: payloadString(event, 'pipeline_id'),
```
  그리고 `EvidenceMsg` 인터페이스에 `traceId?: string | null`, `pipelineId?: string | null` 필드 추가.

- [ ] **Step 2: evidence 카드에 trace 딥링크 버튼** — evidence 메시지 렌더부에서, `msg.evidenceType === 'trace'`(또는 trace 계열) **이고** `msg.traceId && msg.pipelineId`일 때, 기존 카드 톤 그대로 "Tracing 탭에서 상세 보기" 버튼/링크를 추가하고 `onClick={() => app.openPipelineTrace(msg.pipelineId!, msg.traceId!)}`. 새 색/위젯을 만들지 말고 기존 evidence 카드 안에 텍스트 버튼으로 둔다(예: 기존에 쓰는 링크 클래스 재사용).

  > 정확한 클래스는 기존 evidence 렌더를 읽고 동일 톤으로 맞춘다. 버튼 문구: `추적 탭에서 상세 보기` 또는 `Tracing 탭 열기`.

- [ ] **Step 3: 타입체크**

Run: `cd services/frontend && npx tsc --noEmit`
Expected: 에러 없음

- [ ] **Step 4: 수동 확인(외관/동작)** — 에이전트 run에서 get_traces가 호출돼 trace evidence가 오면, 카드에 "상세 보기"가 뜨고 클릭 시 그 파이프라인 Tracing 탭으로 이동해 해당 traceId waterfall(PR1)이 뜨는지 확인. 카드 톤이 기존 evidence와 동일한지 육안 확인.

- [ ] **Step 5: 커밋**
```bash
git add services/frontend/src/pages/ai/AgentRunPanel.tsx
git commit -m "#498 [feat] AgentRunPanel: trace evidence 카드 + Tracing 탭 딥링크"
```

---

## 완료 후
- PR 본문은 `.github/pull_request_template.md`. Part of #498 (PR2). PR1(#511)에 이어 동일 브랜치 또는 develop 머지 후 새 브랜치 — 실행 시점에 결정.
- 남은 PR3(EDA Connection Guide traceparent 스니펫)은 별도 계획.

## Self-Review 메모(스펙 대비)
- 스펙 §4.2 FastAPI evidence에 trace_id/pipeline_id → Task 1 ✓
- 스펙 §4.3 AgentRunPanel compact 카드 + 딥링크(view 전환 + tab + traceId) → Task 2·3·4 ✓
- 디자인 충실도 → Task 4 정독 게이트 + 기존 카드 클래스 차용 ✓
- traceId 딥링크(정확한 그 trace) → Task 3 TraceTab이 selectedTraceId로 조회 ✓
