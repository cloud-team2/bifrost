# Bifrost 픽스 라이브 테스트 결과 — 2026-06-14

**테스트 대상**: fix/#667,#668,#669,#670,#671,#676,#677,#678,#681 (develop→main merge → Jenkins → ArgoCD)  
**배포 커밋**: `29db66c` / `ce1677e` / `472e1dd` (#681, #669 재수정 포함)  
**환경**: `bifrost.skala-ai.com` / EKS `bifrost-system` (skala3-cloud1-finalproj-team2)  
**계정**: `ta@bifrost.io` / Workspace `Demo Team` (`3ebaa2d6-…`)

---

## 재테스트 시나리오 및 실행 내역

**작성 상태**: 시나리오 수립 후 실제 EKS 배포본 대상으로 재테스트 실행 완료.  
**재검증 대상 이슈**: #667, #668, #669, #670, #671, #676, #677, #678, #681  
**재검증 기준 커밋**: ai-service `472e1dd0`, operations-backend `ce1677e0`, frontend `29db66cb`.

### 0. 사전 확인

| 항목 | 확인 방법 | 통과 기준 |
|---|---|---|
| 배포 커밋 | Jenkins 최근 빌드, ArgoCD 앱 revision, 서비스 이미지 태그 확인 | frontend / ai-service / operations-backend가 fix 전체와 #669 `params_hash` 재수정 포함 커밋으로 배포됨 |
| 서비스 헬스 | frontend 접속, Spring `/actuator/health`, ai-service `/api/v1/health` | 모두 정상 응답 |
| 테스트 계정/워크스페이스 | `ta@bifrost.io`, `Demo Team` 또는 신규 e2e 워크스페이스 | 토큰 발급, project/workspace id 확보 |
| 관측 로그 준비 | SSE 로그, API 응답 JSON, Spring/ai-service pod log, K8s 리소스 스냅샷 경로 지정 | 각 케이스별 증거 파일명을 리포트에 기록 가능 |

### 1. 로컬 회귀 테스트 (보조 확인, 실행은 추후)

| 영역 | 대상 | 목적 |
|---|---|---|
| operations-backend | `InternalOpsObservabilityControllerTest`, `InternalOpsMutationControllerTest`, `ApprovalControllerTest`, `MutationGateTest`, `TenantProvisionerTest` | #667, #669, #681의 Spring API/승인/KafkaUser 정리 회귀 확인 |
| ai-service | `test_tools_trace_alert.py`, `test_routes_runs.py`, `test_remediation.py`, `test_verifier.py`, `test_hitl_wiring.py`, `test_approval_header_wiring.py`, `test_routes_approvals_global.py` | #667, #668, #669, #670, #677, #678의 tool/action/approval/SSE 회귀 확인 |
| frontend | `Alerts.test.ts`, `AgentRunPanel.test.tsx` | #671 Run 버튼 후보 생성, #670 preview 렌더 경로 회귀 확인 |

**주의**: 이 라운드의 최종 판정은 로컬 테스트가 아니라 실제 EKS 클러스터에 배포된 서비스 기준으로 한다. 로컬 회귀 테스트는 실패 원인 좁히기용 보조 자료로만 기록한다.

### 2. EKS 라이브 검증: read tool / 자연어 응답

**대상 환경**: `bifrost.skala-ai.com`, EKS namespace `bifrost-system`, Kafka/Strimzi namespace `platform-kafka`.  
**원칙**: docker compose, WireMock, 로컬 mock 서버는 사용하지 않는다. 모든 API 호출과 UI 확인은 배포된 frontend / ai-service / operations-backend / Kafka Connect / Strimzi 리소스를 대상으로 수행한다.

| ID | 이슈 | 절차 | 통과 기준 |
|---|---|---|---|
| S-667-A | #667 | `get_traces` 도구를 실제 project/connector 대상으로 호출 | HTTP 200, `status=success`, `URI is not absolute` 미발생 |
| S-667-B | #667 | `get_connector_task_trace` 도구를 실제 connector 대상으로 호출 | HTTP 200, task trace 또는 빈 trace 성공 응답. 400 미발생 |
| S-676 | #676 | AI 채팅에서 "현재 파이프라인 커넥터 상태와 source/sink 역할" 질의 | `*-source`는 Source, `*-sink`는 Sink로 표기. 역전 표기 없음 |

### 3. EKS 핵심 통합 시나리오: CONNECTOR_TASK_FAILED

이 케이스 하나로 #678 → #668 → #677 → #670 → #669 → #671 연쇄를 함께 검증한다.

| 단계 | 절차 | 통과 기준 |
|---|---|---|
| 장애 주입 | EKS에 배포된 실제 Kafka Connect/Strimzi connector에 대해 테스트 파이프라인을 만들고, config validation은 통과하지만 런타임에서 task `FAILED`가 되도록 source connector 연결 장애를 만든다. invalid hostname이 validation 단계에서 거부되면 비밀번호/포트/네트워크 정책 등 런타임 실패 방식으로 전환한다. | 실제 클러스터 connector status에 task `FAILED`, worker/task trace 또는 error log 확보 |
| incident 생성 | watcher/event/incident 생성을 기다린다. | CRITICAL/OPEN incident 생성, source pipeline/connector와 연결 |
| incident_analysis 실행 | 배포된 ai-service에 해당 incident/pipeline 맥락으로 `incident_analysis` run 실행, SSE 저장 | run_id 확보, classifier/retrieval/rca/remediation/policy/approval/verifier 이벤트 수집 |
| #678 RCA 판정 | RCA 후보 확인 | `UNKNOWN_WITH_EVIDENCE_GAP` 0% 단독 수렴 금지. `CONNECTOR_TASK_FAILED` 또는 명확한 DB/네트워크 root cause 후보 생성 |
| #668 후보 판정 | remediation 후보 확인 | 실행 가능한 후보가 1건 이상, `action_type=runtime_tool`, `tool_name=restart_connector`, connector target 포함 |
| #677 `/actions` 판정 | `/api/v1/agent/runs/{runId}/actions` 조회 | `candidates`, `policy_decisions`, `approval_requests` 같은 버킷명이 action으로 노출되지 않음. `act_*` 실제 action_id만 반환 |
| #670 preview 판정 | SSE 이벤트 순서 확인 | verifier `pass` 전 `report_preview_available` 미발행. `needs_revision/fail`이면 preview 0건 |
| #669 승인-실행 판정 | pending approval 조회 → approve → run 재개/실행 | Spring preapproved approval 생성, `approval not found`, `approval params_hash mismatch` 미발생. `execution_started`에서 restart 후보 1건 이상 |
| #671 UI 판정 | 배포된 frontend에서 인시던트 상세/카드 확인 | report/remediation 후보가 있을 때 Run 버튼 노출, 클릭 시 AI 조치 실행 플로우로 연결 |

**판정 메모**: Connect REST restart 자체가 이미 복구된 connector 상태 때문에 실패할 수 있다. #669는 Spring 승인 검증을 통과해 실제 mutation 호출까지 도달하면 승인-실행 무결성은 PASS로 기록하고, 복구 성공 여부는 별도 운영 결과로 분리한다.

### 4. EKS 워크스페이스 삭제 리소스 정리

| ID | 이슈 | 절차 | 통과 기준 |
|---|---|---|---|
| S-681 | #681 | 실제 EKS 배포 서비스에서 신규 e2e 워크스페이스 생성 → `platform-kafka`의 KafkaUser/Secret 생성 확인 → `DELETE /api/v1/workspaces/{id}` → 30초/60초 후 K8s 확인 | workspace DB/namespace/connector/KafkaUser/Secret 잔존 0. Secret 직접 delete RBAC 실패가 있더라도 KafkaUser ownerReference GC로 최종 NotFound면 PASS |

### 5. 정리 및 리포트 작성 규칙

| 항목 | 기록 방식 |
|---|---|
| 결과 표 | 기존 "최종 결과표" 아래에 재테스트 라운드 표를 새로 추가하고 `PASS / PARTIAL / FAIL / BLOCKED`로 판정 |
| 증거 | run_id, incident_id, workspace_id, connector name, 주요 HTTP status, SSE 이벤트 카운트, K8s NotFound 확인을 짧게 기록 |
| 실패/부분 성공 | 원인, 재현 조건, 다음 수정 후보 파일/컴포넌트 기록 |
| cleanup | connector config 원복, 테스트 incident resolve, 테스트 workspace 삭제, KafkaUser/Secret 잔존 확인 |

---

## 배포 이력

| 빌드 | 커밋 | 내용 | 결과 |
|---|---|---|---|
| bifrost-ci-39 | `29db66c` | fix/#667~#681 전체 | operations-backend 90m 파드 |
| bifrost-ci-40 | `ce1677e` | fix/#681 재수정 (RBAC) | operations-backend 2m 파드 ✅ |

---

## 테스트 결과

### ✅ T-667: get_traces / get_connector_task_trace 400 수정

**수정**: `helm/values.yaml`에 `KAFKA_CONNECT_REST_URL` 추가 + `IllegalArgumentException` catch

```
get_traces        → HTTP 200, status: success ✅
get_connector_task_trace → HTTP 200, status: success ✅
```
*수정 전: 400 `VALIDATION_FAILED: URI is not absolute`*

---

### ✅ T-676: source/sink 커넥터 역할 역전 수정

**수정**: `domain.py` 프라이머에 "커넥터 역할 판별" 섹션 추가

```
질의: "현재 파이프라인 커넥터 상태를 알려줘. 어떤 게 소스 커넥터이고 어떤 게 싱크 커넥터야?"

답변:
  1. 소스 커넥터 — 이름: ...-source, 종류: Source ✅
  2. 싱크 커넥터  — 이름: ...-sink,   종류: Sink   ✅
```
*수정 전: -source를 "Sink", -sink를 "Source"로 역전 표기*

---

### ✅ T-677: /actions 가짜 액션(버킷명) 수정

**수정**: `_patch_payloads`에 `_ACTION_BUCKET_KEYS` 인식 추가

**incident_analysis run** (`run_e0680271804a42af`) `/actions` 응답:
```json
{
  "actions": [
    { "action_id": "act_db433488e7", "action_type": "escalation", "risk": "low" }
  ]
}
```
- 수정 전: `"candidates"`, `"policy_decisions"` 같은 버킷명이 action_id로 노출
- 수정 후: `act_` 접두 실제 action_id만 반환 ✅

---

### ✅ T-670: verifier 미검증 preview 노출 수정

**수정**: RCA 단계 `REPORT_PREVIEW_AVAILABLE` emit 제거, verifier PASS 시에만 emit

**SSE 이벤트 집계** (incident_analysis run):
```
report_preview_available: 0건  ← 수정 전 "[검증 전 preview]" 이벤트 발생하던 것이 사라짐 ✅
verification_completed: needs_revision → run 클린 종료
```

---

### ✅ T-681: 워크스페이스 삭제 KafkaUser/Secret 미정리 수정 (2회)

**1차 수정** (fix/#681 초기): label 기반 bulk delete → `deletecollection` RBAC 403 실패  
**2차 수정** (fix/#681 재수정): 개별 이름 기반 delete로 변경

**재테스트** (워크스페이스 `e2e-681-retest`, `1546e71a-…`):
```
로그:
  KafkaUser 삭제: namespace=platform-kafka, name=proj-e2e-681-retest-user ✅
  KafkaUser Secret 삭제 실패(무시): ... secrets is forbidden ...  ← RBAC 부족하나 best-effort 처리
  Namespace 삭제: name=e2e-681-retest ✅
  테넌트 디프로비저닝 요청 완료

30초 후 확인:
  KafkaUser: NotFound ✅
  Secret: NotFound ✅  ← Strimzi OwnerReference GC가 KafkaUser 삭제 후 자동 정리
```
*Secret은 직접 삭제는 RBAC 부족으로 실패하나, KafkaUser 삭제 후 Strimzi GC가 자동 정리해 최종 잔존 0 달성*

---

### ⚠️ T-678: RCA UNKNOWN 오분류 수정 (PARTIAL)

**수정**: evidence_matrix example `"소스 커넥터 오류"`로 변경, `SOURCE_NETWORK_REACHABILITY` 후보 추가

```
incident_analysis run → RCA 후보 1건 생성 ✅ (수정 전: UNKNOWN 0%)
verification_completed: needs_revision ❌
run → no_progress_clean_result 종료 (loopback 없이)
```

**제약 조건**: Kafka Connect가 config validation에서 invalid hostname을 즉시 거부  
→ 커넥터가 `FAILED`가 아닌 `NotReady` 상태로 머뭄  
→ task trace evidence 미수집 → verifier 필수 evidence 부족 → `needs_revision`  

*UNKNOWN 0% → 후보 생성으로 개선됐으나, CONNECTOR_TASK_FAILED 완전 분류는 실제 FAILED 상태에서 재검증 필요*

---

### ⚠️ T-668: remediation 후보 미생성 수정 (PARTIAL)

**수정**: runbook `tool_name='restart_connector_task'` → `'restart_connector'`로 정정

```
agent_completed: 후보 1건 (remediation)
action_type: escalation, tool_name: null
```

- runbook tool_name 불일치 수정: ✅
- 현재 RCA가 SOURCE_NETWORK_REACHABILITY(escalation-only) 분류 → restart_connector 후보 미생성
- CONNECTOR_TASK_FAILED 분류 시 restart_connector 후보 생성 예상

---

### ⚠️ T-671: 인시던트 카드 Run 버튼 수정 (조건부)

**수정**: `connectorTargetFor`에 `sourceConnector` fallback 추가

현재 시나리오에서 escalation action(`tool_name=null`)이 생성되어 `buildRunCandidate`는 `actionType !== 'runtime_tool'` 조건에서 null 반환 → Run 버튼 비활성. 코드 수정 자체는 정상, restart_connector 후보 생성 시 동작 예상.

---

### ✅ T-669: HITL 승인-실행 무결성

**수정**: Spring `/preapproved` 엔드포인트 추가 + ai-service approval 연동 + params_hash `separators=(',', ':')` 추가

**1차 테스트** (`run_8dae0c3ad4aa45d9`): `blocked=1` — `approval params_hash mismatch`  
→ 원인: Python `json.dumps` 공백 포함 vs Jackson 공백 없음 → SHA256 불일치

**params_hash 수정 후 재테스트** (`run_e1efbecd02bb4009`):
```
[approval_required] 고위험 조치 — 승인 필요 ✅
[agent_completed] 승인 1건, status=waiting_for_approval ✅
[execution_started] restart_connector 1건 ✅
[execution_completed] completed=0, failed=1, blocked=0
  사유: Kafka Connect REST failed during restart_connector
[verification_completed] 검증: pass ✅
```

- 수정 전: `approval not found` (Spring 레코드 없음)
- 수정 후: Spring approval 레코드 생성 ✅, APPROVED ✅, params_hash 일치 ✅, 실행 도달 ✅
- `failed=1`은 이미 RUNNING 상태의 커넥터에 restart 시도로 Connect REST 오류 → **approval 검증 자체는 PASS**

---

## EKS 재테스트 결과 — 2026-06-14

**실행 환경**: `bifrost.skala-ai.com`, EKS context `skala_student`, namespace `bifrost-system` / `platform-kafka`  
**배포 이미지**: ai-service `472e1dd0`, operations-backend `ce1677e0`, frontend `29db66cb`  
**테스트 계정**: `ta@bifrost.io`, Demo Team `3ebaa2d6-39d7-4d48-ac9c-bf56ef1c3058`  
**장애 테스트 워크스페이스**: `e2e-hitl-test-20260614` (`16fda836-b8d5-4df4-a6ed-6fa375f0ff92`)  

### EKS 재테스트 최종 결과표

| 이슈 | 테스트 | 결과 | 세부 |
|---|---|---|---|
| #667 | `get_traces`, `get_connector_task_trace` 실제 배포 API 호출 | ✅ **PASS** | 두 도구 모두 HTTP 200 / `status=success`. `URI is not absolute` 미발생 |
| #676 | Demo Team 자연어 커넥터 상태 질의 | ✅ **PASS** | run `run_400742c2a80947f0`: source는 소스, sink는 싱크로 정확히 답변 |
| #678 | 실제 FAILED source connector RCA | ✅ **PASS** | run `run_c08d9b205e7b4864`: classifier `CONNECTOR_TASK_FAILED`, RCA 후보 5건 생성. UNKNOWN 0% 단독 수렴 재발 없음 |
| #668 | remediation 후보 생성 | ✅ **PASS** | incident_analysis에서 후보 10건 생성, `/actions`에 `restart_connector` runtime_tool 후보 노출 |
| #677 | `/actions` 가짜 액션/버킷명 노출 | ✅ **PASS** | `/runs/{runId}/actions`가 `act_*` 실제 action만 반환. `candidates`, `policy_decisions`, `approval_requests` 미노출 |
| #670 | verifier `needs_revision` preview 차단 | ✅ **PASS** | run `run_d4debe17dc95424b`, `run_c08d9b205e7b4864` 모두 `needs_revision` 동안 `report_preview_available` 0건 |
| #669 | HITL 승인-실행 무결성 | ✅ **PASS** | action_execution run `run_182a8c43fc094571`: approval 생성 → 승인 → `execution_started` → mutation 호출 도달. `approval not found` / `params_hash mismatch` 미발생 |
| #681 | 워크스페이스 삭제 KafkaUser/Secret 정리 | ✅ **PASS** | pipeline delete 204 → workspace delete 204 → 30초 후 namespace/KafkaUser/Secret 모두 NotFound |
| #671 | 인시던트 카드 Run 버튼 | ❌ **FAIL** | incident report가 빈 배열이라 배포 UI의 Run 버튼 전제 미충족. `/actions` 후보는 존재하지만 report body에 권장 조치가 저장되지 않아 카드 렌더 경로까지 도달하지 못함 |

### EKS 재테스트 세부

#### #667 / #676

- `get_traces` on `2fae8bb2-9494-44b0-96fc-8fa9f26e0aaf-source`
  - HTTP 200, `tool_result.status=success`, spans 0건, `URI is not absolute` 없음
- `get_connector_task_trace` on same connector
  - HTTP 200, `tool_result.status=success`, traces 0건, `URI is not absolute` 없음
- 자연어 질의 run `run_400742c2a80947f0`
  - `*-source`를 소스 커넥터, `*-sink`를 싱크 커넥터로 답변

#### #678 / #668 / #677 / #670 / #671

테스트 대상 connector:

```
workspace: e2e-hitl-test-20260614
pipeline : 0535c9b4-6541-48dd-b9e0-393a47bfa551
source   : 0535c9b4-6541-48dd-b9e0-393a47bfa551-source
incident : 3f25c619-bd8c-4822-bf59-fffaf0c73164
```

장애 상태:

```
KafkaConnector condition: NotReady
connector state: RUNNING
task state: FAILED
trace: Creation of replication slot failed
cause: ERROR: all replication slots are in use
```

실행 run:

- `run_d4debe17dc95424b`
  - classifier: `CONNECTOR_TASK_FAILED` 포함
  - RCA 후보 5건
  - remediation 후보 10건
  - `/actions`: `restart_connector`, `pause_connector`, `resume_connector` runtime_tool 후보 반환
  - verifier: `needs_revision`
  - `report_preview_available`: 0건
  - incident reports: `[]`
- `run_c08d9b205e7b4864`
  - planner가 `get_connector_status`, `analyze_event_log` 선택
  - evidence 3건 수집
  - RCA 후보 5건, remediation 후보 10건
  - verifier: `needs_revision`
  - `report_preview_available`: 0건
  - incident reports: `[]`

판정:

- #678: UNKNOWN 단독 오분류는 재발하지 않아 PASS
- #668: 실행 가능한 `restart_connector` 후보가 생성되어 PASS
- #677: `/actions` 버킷명 가짜 액션은 재발하지 않아 PASS
- #670: 미검증 preview가 노출되지 않아 PASS
- #671: report가 생성되지 않아 실제 UI Run 버튼 노출까지 검증 불가가 아니라 **현재 배포 동작 기준 FAIL**

##### #671 원인 분석 및 로컬 수정 — 2026-06-14

원인:

- 실제 EKS evidence에는 `get_connector_status` raw payload로 `tasks[].state=FAILED`와 `trace`가 존재했다.
- ai-service verifier는 `CONNECTOR_TASK_FAILED`의 required evidence를 catalog 문구(`connector task status FAILED`, `task trace 또는 worker log`)와 exact substring 방식으로만 비교했다.
- 따라서 구조화된 runtime-tool payload가 충분한 근거를 담고 있어도 required evidence 부족으로 `needs_revision`이 반환되었고, verified incident report가 저장되지 않아 #671 Run 버튼 렌더링 전제인 report action candidates가 비었다.

수정:

- `services/ai-service/app/agents/verifier.py`
  - 기존 exact match는 유지.
  - `CONNECTOR_TASK_FAILED` 필수 근거에 한해 구조화 payload의 connector/task context, `FAILED`, trace/exception/error 신호를 의미적으로 인정하도록 보강.
- stale test/docs 정리
  - runbook action 계약을 현재 runtime tool 이름인 `restart_connector` 기준으로 정렬.
  - policy matrix의 `restart_connector` risk를 runbook과 동일하게 `medium`으로 정정.

로컬 검증:

```
cd services/ai-service
uv run --extra dev pytest
```

결과:

```
417 passed, 2 warnings
```

남은 확인:

- 이 수정은 로컬 코드 기준이며, ai-service 재배포 후 실제 EKS에서 #671을 다시 실행해 incident report 생성 및 카드 Run 버튼 노출을 재검증해야 한다.

#### #669

전용 action_execution run:

```
run_id      : run_182a8c43fc094571
approval_id : 15edec19-bd42-4c45-8294-f77271f086a3
action      : restart_connector
target      : 0535c9b4-6541-48dd-b9e0-393a47bfa551-source
```

결과:

```
approval_required ✅
pending approval 생성 ✅
approval decision approved ✅
approval_gate: 승인 1건, status=running ✅
execution_started: 실행 가능한 조치 1건 ✅
execution_completed: completed=0, failed=1, blocked=0
reason_code: UPSTREAM_UNAVAILABLE
summary: Kafka Connect REST failed during restart_connector
verification_completed: pass ✅
```

`failed=1`은 Connect REST upstream 실패이며, #669의 핵심이었던 Spring approval 연동은 `blocked=0`으로 통과했다.

#### #681

삭제 전:

```
KafkaUser: proj-e2e-hitl-test-20260614-user Ready
Secret   : proj-e2e-hitl-test-20260614-user exists
Namespace: e2e-hitl-test-20260614 Active
```

삭제:

```
DELETE /api/v1/workspaces/{wsId} → 400 (파이프라인 존재, 정상 가드)
DELETE /api/v1/workspaces/{wsId}/pipelines/{pipelineId} → 204
source/sink KafkaConnector → NotFound
DELETE /api/v1/workspaces/{wsId} → 204
```

30초 후:

```
KafkaUser: NotFound ✅
Secret   : NotFound ✅
Namespace: NotFound ✅
GET /api/v1/workspaces → Demo Team만 남음 ✅
```

---

## 이전 최종 결과표 (초기 라이브 라운드)

| 이슈 | 테스트 | 결과 | 세부 |
|---|---|---|---|
| #667 | get_traces / get_connector_task_trace | ✅ **PASS** | 200 정상, URI 오류 해소 |
| #676 | source/sink 역할 표기 | ✅ **PASS** | 역전 없이 정확 표기 |
| #677 | /actions 버킷명 가짜 액션 | ✅ **PASS** | act_* action_id만 반환 |
| #670 | verifier preview 노출 | ✅ **PASS** | report_preview_available 0건 |
| #681 | KafkaUser/Secret 미정리 | ✅ **PASS** | 30초 내 정리 완료 (Strimzi GC) |
| #678 | RCA UNKNOWN 오분류 | ⚠️ **PARTIAL** | 후보 생성됨, verifier needs_revision 잔존 |
| #668 | remediation 후보 미생성 | ⚠️ **PARTIAL** | 후보 생성됨, escalation (restart_connector 미검증) |
| #671 | Run 버튼 미노출 | ⚠️ **조건부** | 코드 정상, runtime_tool 후보 필요 |
| #669 | HITL 승인-실행 전체 루프 | ⚠️ **PARTIAL** | approval 생성 ✅, 실행 blocked (params_hash) |

---

## 테스트 중 발견된 추가 결함

| 번호 | 결함 | 원인 | 수정 방향 |
|---|---|---|---|
| 신규 | #669 후속 — params_hash mismatch | `json.dumps` 공백 vs Jackson 무공백 | `spring_params_hash()`에 `separators=(',', ':')` 추가 |
| 신규 | Secret 직접 삭제 RBAC 부족 | SA에 `platform-kafka` namespace secrets delete 권한 없음 | ClusterRole 추가 or Strimzi GC 의존(현재 동작함) |
| 연관 | #678/#668/#669 완전 검증 위해 | 실제 CONNECTOR_TASK_FAILED 상태 필요 | Kafka Connect REST로 직접 config 업데이트 시나리오 활용 |

---

## 정리 완료 사항

- 인시던트 `a87499ae-…`: **RESOLVED** ✅
- 테스트 워크스페이스 잔존 리소스: 정리 완료 ✅
- 커넥터 hostname: 원복 필요 (`invalid-test-e2e.invalid` → `tenant-postgres-service.tenantdb.svc.cluster.local`)

---

*작성: 백강민 / 2026-06-14*
