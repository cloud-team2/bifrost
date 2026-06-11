# 프론트 추적 연동 PR 1 — Spring trace 엔드포인트 + 프론트 Tracing 탭 (구현 계획)

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development(권장) 또는 superpowers:executing-plans로 task 단위 실행. 단계는 `- [ ]` 체크박스.

**Goal:** 파이프라인 상세에 `Tracing` 탭을 추가해 Tempo 분산 trace(source→topic→sink, 어디서 지연/실패)를 풀 waterfall로 보여준다.

**Architecture:** 워크스페이스 스코프 Spring 엔드포인트(`GET …/pipelines/{id}/trace[?traceId=]`)가 기존 `TraceQuery`(Tempo)를 호출해 `TraceSummaryResult`를 반환하고, 프론트는 기존 탭 패턴(`api.xxx(wsId,id)` + useState/useEffect)을 그대로 차용한 `TraceTab` + 공유 `TraceWaterfall` 컴포넌트로 렌더한다.

**Tech Stack:** Spring Boot 3.3/Java 21(operations-backend), React+TS+Tailwind+vitest(frontend). 설계: `docs/superpowers/specs/2026-06-11-frontend-tracing-integration-design.md`.

**디자인 충실도(필수):** 새 프론트 코드는 기존 탭(`ConnectorTab`/`SyncTab` 등, `PipelineDetail.tsx`)의 구조·Tailwind·로딩/에러/빈 상태 패턴을 그대로 따른다. AI 티 금지. **Task 5 전에 Task 4.5(기존 탭 정독)를 반드시 수행.**

---

## 파일 구조

**Spring (operations-backend)**
- Modify: `…/adapters/tempo/TempoClient.java` — `traceById(traceId)` 추가(기존 fetch/parse 재사용)
- Modify: `…/monitoring/query/TraceQuery.java` — `queryById(connectorName, traceId)` 추가
- Modify: `…/pipeline/service/PipelineService.java` — `trace(wsId, principal, id, traceId)` 추가
- Modify: `…/pipeline/controller/PipelineController.java` — `GET …/{id}/trace` 엔드포인트
- Test: `…/monitoring/query/TraceQueryTest.java`(queryById), `…/pipeline/…/PipelineServiceTest`(있으면) 또는 컨트롤러 슬라이스

**Frontend (services/frontend)**
- Modify: `src/lib/api.ts` — `TraceSummaryResponse`/`TraceSpanResponse` 타입 + `api.pipelineTrace(wsId,id,traceId?)`
- Create: `src/lib/traceWaterfall.ts` — 순수 geometry/표시 헬퍼(테스트 대상)
- Create: `src/lib/traceWaterfall.test.ts` — vitest
- Create: `src/components/TraceWaterfall.tsx` — 공유 컴포넌트(full 모드)
- Modify: `src/pages/dev/PipelineDetail.tsx` — `TraceTab` 추가 + CDC/EDA 탭 목록에 `Tracing`

---

## Task 1: TempoClient.traceById + TraceQuery.queryById

**Files:**
- Modify: `services/operations-backend/src/main/java/com/bifrost/ops/adapters/tempo/TempoClient.java`
- Modify: `services/operations-backend/src/main/java/com/bifrost/ops/monitoring/query/TraceQuery.java`
- Test: `services/operations-backend/src/test/java/com/bifrost/ops/monitoring/query/TraceQueryTest.java`

- [ ] **Step 1: 실패 테스트 작성** — `TraceQueryTest`에 추가

```java
@Test
void queryByIdReturnsTraceSummary() {
    TempoClient client = mock(TempoClient.class);
    TraceSpan span = new TraceSpan("sink-put", "platform-connect", 4L, "error", "type mismatch");
    when(client.traceById("abc123"))
            .thenReturn(Optional.of(new TempoTrace("abc123", 9L, true, List.of(span))));
    TraceQuery q = new TraceQuery(true, client);

    TraceSummaryResult r = q.queryById("p1-source", "abc123");

    assertThat(r.traceId()).isEqualTo("abc123");
    assertThat(r.pipelineId()).isEqualTo("p1");
    assertThat(r.status()).isEqualTo("error");
    assertThat(r.spans()).singleElement().extracting(TraceSpan::name).isEqualTo("sink-put");
}

@Test
void queryByIdDisabledReturnsStub() {
    TraceQuery q = new TraceQuery(false, mock(TempoClient.class));
    TraceSummaryResult r = q.queryById("p1-source", "abc123");
    assertThat(r.traceId()).isNull();
    assertThat(r.note()).contains("비활성화");
}

@Test
void queryByIdMissingReturnsStub() {
    TempoClient client = mock(TempoClient.class);
    when(client.traceById("missing")).thenReturn(Optional.empty());
    TraceQuery q = new TraceQuery(true, client);
    assertThat(q.queryById("p1-source", "missing").traceId()).isNull();
}
```

- [ ] **Step 2: 컴파일/실패 확인**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :services:operations-backend:test --tests 'com.bifrost.ops.monitoring.query.TraceQueryTest' --no-configuration-cache --rerun-tasks`
Expected: 컴파일 실패(`traceById`/`queryById` 미정의)

- [ ] **Step 3: TempoClient.traceById 추가** — `recentTrace`의 fetch+요약 로직 재사용. `recentTrace` 안의 `/api/traces/{id}` 호출+`parseTrace`+요약 부분을 `traceById`로 추출하고 `recentTrace`가 그것을 호출하도록 리팩터.

```java
/** traceId로 trace 상세를 직접 조회·요약한다. 없거나 spans 비면 empty. @throws RestClientException 접속 실패 */
public Optional<TempoTrace> traceById(String traceId) {
    if (traceId == null || traceId.isBlank()) {
        return Optional.empty();
    }
    JsonNode detail = restClient.get()
            .uri("/api/traces/{id}", traceId)
            .retrieve()
            .body(JsonNode.class);
    List<TraceSpan> spans = parseTrace(detail);
    if (spans.isEmpty()) {
        return Optional.empty();
    }
    long durationMs = spans.stream().mapToLong(TraceSpan::durationMs).max().orElse(0L);
    boolean error = spans.stream().anyMatch(s -> "error".equals(s.status()));
    return Optional.of(new TempoTrace(traceId, durationMs, error, spans));
}
```

그리고 `recentTrace`의 fetch 부분을 `traceById(traceId)` 호출로 교체(검색으로 얻은 `durationMs`가 더 크면 유지):
```java
// recentTrace 내부, traceId 확보 후:
return traceById(traceId)
        .map(t -> new TempoTrace(t.traceId(), Math.max(durationMs, t.durationMs()), t.error(), t.spans()));
```

- [ ] **Step 4: TraceQuery.queryById 추가**

```java
/** 특정 traceId의 trace 요약. 비활성/미발견/실패 시 stub. */
public TraceSummaryResult queryById(String connectorName, String traceId) {
    String pipelineId = pipelineIdOf(connectorName);
    if (!enabled) {
        return TraceSummaryResult.stub(pipelineId, "Tempo 비활성화 — stub 응답");
    }
    try {
        Optional<TempoTrace> trace = client.traceById(traceId);
        if (trace.isEmpty()) {
            return TraceSummaryResult.stub(pipelineId, "trace 없음: " + traceId);
        }
        TempoTrace t = trace.get();
        return TraceSummaryResult.of(t.traceId(), pipelineId, t.error() ? "error" : "ok", t.durationMs(), t.spans());
    } catch (RestClientException e) {
        log.debug("Tempo queryById 실패(stub): traceId={} cause={}", traceId, e.getMessage());
        return TraceSummaryResult.stub(pipelineId, "Tempo 조회 실패 — stub 응답");
    }
}
```

- [ ] **Step 5: 통과 확인**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :services:operations-backend:test --tests 'com.bifrost.ops.monitoring.query.TraceQueryTest' --tests 'com.bifrost.ops.adapters.tempo.TempoClientTest' --no-configuration-cache --rerun-tasks`
Expected: PASS (queryById 3 + 기존 통과)

- [ ] **Step 6: 커밋**

```bash
git add services/operations-backend/src/main/java/com/bifrost/ops/adapters/tempo/TempoClient.java \
        services/operations-backend/src/main/java/com/bifrost/ops/monitoring/query/TraceQuery.java \
        services/operations-backend/src/test/java/com/bifrost/ops/monitoring/query/TraceQueryTest.java
git commit -m "#<PR1이슈> [feat] TempoClient.traceById + TraceQuery.queryById — trace by id 조회"
```

---

## Task 2: PipelineTopicService.trace + PipelineController 엔드포인트

**Files:**
- Modify: `services/operations-backend/src/main/java/com/bifrost/ops/pipeline/service/PipelineTopicService.java`
- Modify: `services/operations-backend/src/main/java/com/bifrost/ops/pipeline/controller/PipelineController.java`

**선행 정독(3분, 필수):** 모니터링 쿼리는 **PipelineTopicService 경유**가 정설이다 — 컨트롤러의 `metrics`(`@GetMapping("/{id}/metrics")` → `pipelineTopicService.metrics(wsId, principal, id)`)와 `stageStatus`를 읽고, **접근 가드(wsId/principal) 처리·파이프라인/커넥터 조회 방식**을 그대로 모방한다. trace는 `metrics`와 동일한 시그니처 패턴 + `ConnectorNaming.sourceConnectorName(pipelineId)`로 source 커넥터명을 만들어 `TraceQuery`에 위임한다.
> topic은 PR1에서 **null 전달**(TraceQuery가 service.name 범위로 폴백) — Connect REST 의존을 PipelineTopicService에 새로 추가하지 않는다. 커넥터-정밀 topic 연결은 PR2 범위.

- [ ] **Step 1: PipelineTopicService에 `TraceQuery` 주입 + `trace` 추가** — 생성자에 `TraceQuery traceQuery` 파라미터를 추가(기존 의존성 옆)하고, `metrics`가 wsId/principal로 접근·파이프라인을 다루는 방식을 그대로 따라 아래를 추가. `pipelineId`는 `metrics`가 파이프라인을 조회하는 그 방식으로 얻는다(동일 lookup 재사용).

```java
/** 파이프라인 분산 trace 요약(#498, Tracing 탭). traceId 지정 시 그 trace, 없으면 최근. */
public TraceSummaryResult trace(UUID wsId, AuthenticatedUser principal, UUID id, String traceId) {
    // metrics()와 동일한 접근 가드 + 파이프라인 조회 패턴을 사용해 pipelineId를 확보한다.
    // (아래 resolvePipelineId는 metrics가 쓰는 lookup을 그대로 재사용 — 새 lookup 추가 금지)
    UUID pipelineId = /* metrics와 동일 경로로 얻은 PipelineEntity */ id; // 실제로는 조회된 엔티티의 getId()
    String connector = ConnectorNaming.sourceConnectorName(pipelineId);
    return (traceId == null || traceId.isBlank())
            ? traceQuery.query(connector, null)
            : traceQuery.queryById(connector, traceId);
}
```
> 주의: 위 `pipelineId` 라인은 `metrics`의 실제 파이프라인 조회(예: `pipelineRepository.findByIdAndWorkspace…` 또는 access guard 헬퍼)를 **읽고 그대로 복제**해 채운다. import: `com.bifrost.ops.internalops.dto.TraceSummaryResult`, `com.bifrost.ops.monitoring.query.TraceQuery`, `com.bifrost.ops.provisioning.naming.ConnectorNaming`.

- [ ] **Step 2: PipelineController 엔드포인트 추가** — 기존 `metrics`/`stageStatus` GET 패턴과 동일

```java
/** 분산 trace 요약(#498, Tracing 탭). traceId 지정 시 그 trace, 없으면 최근. */
@GetMapping("/{id}/trace")
public TraceSummaryResult trace(@PathVariable UUID wsId,
                                @PathVariable UUID id,
                                @RequestParam(required = false) String traceId,
                                @AuthenticationPrincipal AuthenticatedUser principal) {
    return pipelineTopicService.trace(wsId, principal, id, traceId);
}
```
(import `com.bifrost.ops.internalops.dto.TraceSummaryResult`.)

- [ ] **Step 3: 생성자 변경에 따른 테스트/주입부 보정** — `PipelineTopicService` 생성자에 인자를 추가했으므로, 이를 직접 `new` 하는 테스트가 있으면 `mock(TraceQuery.class)`를 추가한다(없으면 Spring DI라 무변경). `grep -rn "new PipelineTopicService(" services/operations-backend/src`로 확인.

- [ ] **Step 4: 전체 빌드 통과 확인**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :services:operations-backend:test --no-configuration-cache`
Expected: BUILD SUCCESSFUL, failures=0 (기존 + 신규)

- [ ] **Step 5: 커밋**

```bash
git add services/operations-backend/src/main/java/com/bifrost/ops/pipeline/service/PipelineTopicService.java \
        services/operations-backend/src/main/java/com/bifrost/ops/pipeline/controller/PipelineController.java
git commit -m "#<PR1이슈> [feat] GET .../pipelines/{id}/trace — 워크스페이스 스코프 trace 요약"
```

---

## Task 3: 프론트 API 타입 + pipelineTrace 메서드

**Files:**
- Modify: `services/frontend/src/lib/api.ts`

- [ ] **Step 1: 응답 타입 추가** (`/* 응답 타입 */` 구역, 기존 `interface` 스타일과 동일)

```ts
export interface TraceSpanResponse {
  name: string
  service: string
  durationMs: number
  status: string          // 'ok' | 'error'
  error: string | null
}
export interface TraceSummaryResponse {
  traceId: string | null
  pipelineId: string | null
  status: string          // 'ok' | 'error' | 'unknown'
  durationMs: number
  spans: TraceSpanResponse[]
  note: string | null
}
```

- [ ] **Step 2: api 객체에 메서드 추가** (`listPipelineConnectors` 등 옆)

```ts
  pipelineTrace: (wsId: string, id: string, traceId?: string) =>
    request<TraceSummaryResponse>(
      'GET',
      `/api/v1/workspaces/${wsId}/pipelines/${id}/trace${traceId ? `?traceId=${encodeURIComponent(traceId)}` : ''}`,
    ),
```

- [ ] **Step 3: 타입체크**

Run: `cd services/frontend && npx tsc --noEmit`
Expected: 에러 없음

- [ ] **Step 4: 커밋**

```bash
git add services/frontend/src/lib/api.ts
git commit -m "#<PR1이슈> [feat] api.pipelineTrace + Trace 응답 타입"
```

---

## Task 4: 순수 헬퍼 traceWaterfall.ts + vitest

**Files:**
- Create: `services/frontend/src/lib/traceWaterfall.ts`
- Create: `services/frontend/src/lib/traceWaterfall.test.ts`

- [ ] **Step 1: 실패 테스트 작성**

```ts
import { describe, it, expect } from 'vitest'
import { waterfallBars } from './traceWaterfall'
import type { TraceSpanResponse } from './api'

const spans: TraceSpanResponse[] = [
  { name: 'source-poll', service: 'platform-connect', durationMs: 5, status: 'ok', error: null },
  { name: 'sink-put', service: 'platform-connect', durationMs: 15, status: 'error', error: 'type mismatch' },
]

describe('waterfallBars', () => {
  it('가장 긴 span 기준으로 너비 %를 낸다', () => {
    const bars = waterfallBars(spans)
    expect(bars[0].widthPct).toBe(100 / 3)   // 5 / 15
    expect(bars[1].widthPct).toBe(100)        // 15 / 15
  })
  it('error span을 표시한다', () => {
    expect(waterfallBars(spans)[1].isError).toBe(true)
  })
  it('빈 입력은 빈 배열', () => {
    expect(waterfallBars([])).toEqual([])
  })
})
```

- [ ] **Step 2: 실패 확인**

Run: `cd services/frontend && npx vitest run src/lib/traceWaterfall.test.ts`
Expected: FAIL (`waterfallBars` 없음)

- [ ] **Step 3: 구현**

```ts
import type { TraceSpanResponse } from './api'

export interface WaterfallBar {
  span: TraceSpanResponse
  widthPct: number      // 가장 긴 span 대비 비율(0~100)
  isError: boolean
}

/** span 목록을 최장 span 기준 너비 %로 환산한다(렌더는 컴포넌트가 담당, 로직만 분리). */
export function waterfallBars(spans: TraceSpanResponse[]): WaterfallBar[] {
  if (spans.length === 0) return []
  const max = Math.max(...spans.map((s) => s.durationMs), 1)
  return spans.map((s) => ({
    span: s,
    widthPct: (s.durationMs / max) * 100,
    isError: s.status === 'error',
  }))
}
```

- [ ] **Step 4: 통과 확인**

Run: `cd services/frontend && npx vitest run src/lib/traceWaterfall.test.ts`
Expected: PASS (3)

- [ ] **Step 5: 커밋**

```bash
git add services/frontend/src/lib/traceWaterfall.ts services/frontend/src/lib/traceWaterfall.test.ts
git commit -m "#<PR1이슈> [feat] waterfallBars 헬퍼 + 테스트"
```

---

## Task 4.5: 기존 탭 정독 (디자인 충실도, 코드 변경 없음)

- [ ] `services/frontend/src/pages/dev/PipelineDetail.tsx`의 `ConnectorTab`(≈587–636)와 `SyncTab` 본문을 읽고 다음을 메모:
  - 에러/로딩/빈 상태 컨테이너 클래스: `flex flex-col items-center justify-center rounded-xl border border-dashed border-gray-200 bg-white py-16`, 텍스트 `text-[13px] text-gray-400`, 아이콘 `<Icon name="alert|zap" size={24} className="mb-2 text-rose-300|text-gray-300" />`
  - 데이터 fetch: `const app = useApp(); const wsId = app.currentProject?.id` + `useEffect`(cancelled 플래그) + `api.xxx(wsId, edge.id).then(...).catch(() => setError(true))`
  - 색 토큰: 정상=emerald/green 계열, 에러=rose 계열, 보조 텍스트=gray-400/500
- [ ] `Task 5/6`는 이 패턴을 **그대로** 쓴다(새 색/그라데이션/이모지 금지).

---

## Task 5: TraceWaterfall 공유 컴포넌트 (full 모드)

**Files:**
- Create: `services/frontend/src/components/TraceWaterfall.tsx`

> 기존 탭의 색/간격/타이포를 그대로 차용. 컴포넌트 렌더 테스트는 현재 셋업(vitest, jsdom 없음)에 없으므로 만들지 않는다 — 로직은 Task 4에서 테스트됨, 외관은 수동 확인.

- [ ] **Step 1: 컴포넌트 작성**

```tsx
import type { TraceSummaryResponse } from '../lib/api'
import { waterfallBars } from '../lib/traceWaterfall'

/** 분산 trace 요약을 source→sink span waterfall로 렌더(#498). full 모드. */
export function TraceWaterfall({ trace }: { trace: TraceSummaryResponse }) {
  if (!trace.traceId) {
    // stub(Tempo 비활성/미발견) — 기존 빈 상태 패턴
    return (
      <div className="flex flex-col items-center justify-center rounded-xl border border-dashed border-gray-200 bg-white py-16">
        <p className="text-[13px] text-gray-400">{trace.note ?? '표시할 trace가 없습니다'}</p>
      </div>
    )
  }
  const bars = waterfallBars(trace.spans)
  const isError = trace.status === 'error'
  return (
    <div className="rounded-xl border border-gray-200 bg-white p-4">
      <div className="mb-3 flex items-center gap-2 text-[13px]">
        <span className="font-mono text-gray-500">{trace.traceId.slice(0, 12)}</span>
        <span className={isError ? 'text-rose-500' : 'text-emerald-600'}>{trace.status}</span>
        <span className="text-gray-400">· {trace.durationMs}ms</span>
      </div>
      <div className="space-y-1.5">
        {bars.map((b, i) => (
          <div key={i} className="flex items-center gap-2 text-[12px]">
            <span className="w-40 shrink-0 truncate text-gray-600">{b.span.name}</span>
            <div className="relative h-4 flex-1 rounded bg-gray-50">
              <div
                className={`absolute inset-y-0 left-0 rounded ${b.isError ? 'bg-rose-400' : 'bg-emerald-400'}`}
                style={{ width: `${b.widthPct}%` }}
              />
            </div>
            <span className="w-14 shrink-0 text-right tabular-nums text-gray-400">{b.span.durationMs}ms</span>
          </div>
        ))}
      </div>
      {bars.filter((b) => b.isError).map((b, i) => (
        <p key={i} className="mt-2 text-[12px] text-rose-500">{b.span.name}: {b.span.error}</p>
      ))}
    </div>
  )
}
```

- [ ] **Step 2: 타입체크**

Run: `cd services/frontend && npx tsc --noEmit`
Expected: 에러 없음

- [ ] **Step 3: 커밋**

```bash
git add services/frontend/src/components/TraceWaterfall.tsx
git commit -m "#<PR1이슈> [feat] TraceWaterfall 공유 컴포넌트(full)"
```

---

## Task 6: TraceTab + 탭 목록에 Tracing 추가

**Files:**
- Modify: `services/frontend/src/pages/dev/PipelineDetail.tsx`

- [ ] **Step 1: TraceTab 함수 추가** (`ConnectorTab` 패턴 그대로, 파일 내 다른 탭 함수 옆)

```tsx
function TraceTab({ edge }: { edge: Edge }) {
  const app = useApp()
  const wsId = app.currentProject?.id
  const [trace, setTrace] = useState<TraceSummaryResponse | null>(null)
  const [error, setError] = useState(false)

  useEffect(() => {
    if (!wsId) return
    let cancelled = false
    setTrace(null)
    setError(false)
    api.pipelineTrace(wsId, edge.id)
      .then((t) => { if (!cancelled) setTrace(t) })
      .catch(() => { if (!cancelled) setError(true) })
    return () => { cancelled = true }
  }, [wsId, edge.id])

  if (error) {
    return (
      <div className="flex flex-col items-center justify-center rounded-xl border border-dashed border-gray-200 bg-white py-16">
        <Icon name="alert" size={24} className="mb-2 text-rose-300" />
        <p className="text-[13px] text-gray-400">추적 정보를 불러오지 못했습니다</p>
      </div>
    )
  }
  if (trace === null) {
    return (
      <div className="flex items-center justify-center rounded-xl border border-dashed border-gray-200 bg-white py-16 text-[13px] text-gray-400">
        불러오는 중…
      </div>
    )
  }
  return (
    <div className="space-y-4">
      {edge.pattern === 'fan-out' && (
        <p className="text-[12px] text-gray-400">
          EDA는 source→topic 구간까지 표시됩니다. consumer(고객 앱)는 같은 traceId로 고객 관측도구에서 이어볼 수 있습니다.
        </p>
      )}
      <TraceWaterfall trace={trace} />
    </div>
  )
}
```
(상단 import에 `import { TraceWaterfall } from '../../components/TraceWaterfall'` 와 `TraceSummaryResponse`(api.ts에서) 추가.)

- [ ] **Step 2: 탭 목록 + 렌더 분기에 Tracing 추가** (≈43–46, 124–130)

```tsx
// tabs 정의:
const tabs = isEda
  ? ['Overview', 'Consumers', 'Connector', 'Messages', 'Connection Guide', 'Tracing']
  : ['Overview', 'Topic', 'Connector', 'Messages', 'Table Mapping', 'Tracing']

// 렌더 분기에 추가:
{tab === 'Tracing' && <TraceTab edge={edge} />}
```

- [ ] **Step 3: 타입체크 + 빌드**

Run: `cd services/frontend && npx tsc --noEmit && npm run test`
Expected: tsc 에러 없음, vitest 전체 PASS

- [ ] **Step 4: 수동 확인(외관/디자인 충실도)** — dev 서버에서 CDC·EDA 파이프라인 상세의 `Tracing` 탭이 기존 탭과 동일한 톤(테두리/색/간격/빈 상태)으로 뜨는지, waterfall이 기존 카드 톤과 어울리는지 육안 확인. (tempo.enabled=true dev면 실데이터, 아니면 빈 상태)

- [ ] **Step 5: 커밋**

```bash
git add services/frontend/src/pages/dev/PipelineDetail.tsx
git commit -m "#<PR1이슈> [feat] 파이프라인 상세 Tracing 탭 — TraceWaterfall 연동"
```

---

## 완료 후
- 이슈-우선 규칙: 이 PR용 이슈를 먼저 만들고 `feat/#<n>` 브랜치에서 작업(현재 spec 브랜치 `feat/#498`에 이어가거나 별도 브랜치). PR 본문은 `.github/pull_request_template.md`.
- PR 2(FastAPI evidence + AgentRunPanel compact 카드 + traceId 딥링크), PR 3(EDA Connection Guide traceparent 스니펫)은 각각 별도 계획.

## Self-Review 메모(스펙 대비)
- 스펙 §4.1 Spring 엔드포인트/queryById/traceById → Task 1·2 ✓
- 스펙 §4.3 TraceWaterfall(full)/TraceTab/탭 라벨 Tracing → Task 5·6 ✓
- 스펙 §3.1 디자인 충실도 → Task 4.5 정독 게이트 + 기존 클래스 차용 ✓
- 스펙 §6 stub 빈 상태 → TraceWaterfall stub 분기 + TraceTab 로딩/에러 ✓
- 범위 밖(PR1): AgentRunPanel 카드·딥링크(PR2), topic 정밀 연결(PR2), EDA 스니펫(PR3) — 의도적 분리
