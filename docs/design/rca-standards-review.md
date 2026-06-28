# RCA·장애대응 에이전트 표준 대비 검토 및 코드 개선 계획

| 항목 | 내용 |
|---|---|
| 작성일 | 2026-06-19 |
| 범위 | `ai-service`의 RCA·Remediation 에이전트, `operations-backend`의 인시던트/알림 생성 |
| 목적 | 현재 구현의 공격받을 만한 지점과 보완 필요 사항을 표준·논문·벤더 기준으로 식별하고, 코드 단 개선 작업의 우선순위·완료 조건·발표 반영 근거를 정의한다. |
| 방법 | 코드베이스 직접 분석 + 설계문서 대조 + 외부 표준/논문 다중출처 조사(적대적 검증으로 확정된 주장만 인용) |

> 본문의 NIST 인용은 모두 **권고(voluntary/suggested actions)** 이며, "요구"가 아니다.

## 이 문서의 용도

이 문서는 발표 방어용 해설이 아니라 **구현 개선을 위한 기준 문서**다. 현재 RCA·장애대응 에이전트는 이미 카탈로그 기반 RCA, 증거 매트릭스, UNKNOWN 폴백, 승인 게이트 같은 핵심 안전장치를 갖추고 있고, 이후 자동 롤백·run 재현성 manifest·gold set·SLI/SLO routing 같은 일부 운영 보강도 코드에 들어갔다. 다만 실제 운영 수준으로 보려면 아직 남은 gap을 정직하게 분리해야 한다.

따라서 이 문서의 1차 목적은 공격받을 만한 포인트를 숨기는 것이 아니라, 오히려 명확히 드러내고 코드 개선 작업으로 전환하는 것이다. 자동 롤백, run 단위 재현성, confidence 캘리브레이션, RCA 평가셋, 사용자 영향 SLI/SLO, burn-rate 알림, 인과/상관 증거 구분은 발표 문구가 아니라 실제 구현과 검증 대상으로 관리한다. 구현된 항목은 현재 동작과 한계를 함께 적고, 남은 항목은 로드맵으로 유지한다.

발표에서는 이 문서를 "우리가 이미 완벽하다"는 근거로 쓰지 않는다. 대신 **현재 한계와 개선 방향을 표준 기준으로 식별했고, 그 기준에 맞춰 코드 개선을 진행한다**는 설명에 사용한다. 구현이 완료된 항목은 발표에서 실제 개선 결과로 제시하고, 아직 남은 항목은 로드맵과 검증 계획으로 제시한다.

## 읽기 전 요약

이 문서는 **우리 RCA·장애대응 에이전트가 실제 운영 수준에 도달하려면 무엇을 코드로 보강해야 하는지** 정리한 자료다. 핵심 질문은 하나다.

> 지금 구현이 산업 표준과 학술 근거에 비춰 충분히 안전하고, 재현 가능하고, 설명 가능한가?

결론부터 말하면, 현재 구조는 "로그를 LLM에 던지고 그럴듯한 답을 받는 위험한 방식"은 아니다. Bifrost는 이미 카탈로그, 증거 매트릭스, confidence, UNKNOWN 폴백, 승인 게이트를 갖추고 있다. 자동 롤백, run 재현성 manifest, 운영자 피드백 기반 gold set, threshold registry, SLI/SLO burn-rate routing도 기본 구현이 들어갔다. 다만 실행 모델 alias 사용, instrumentation 완성도, 정기 평가·캘리브레이션, baseline 기반 SLO 보정처럼 아직 보강해야 할 부분이 남아 있다.

### 표준이 보는 안전한 RCA 방식

표준과 연구가 공통적으로 권고하는 방향은 다음과 같다.

1. **RCA 판단은 근거에 묶여 있어야 한다.**
   - 원본 로그를 자유롭게 요약하게 두는 것이 아니라, 카탈로그, 증거 규칙, 출처 인용 같은 grounding 위에서 판단해야 한다.
2. **불확실하면 단정하지 않아야 한다.**
   - 증거가 부족하거나 후보 간 차이가 작으면 UNKNOWN 또는 보류(abstain)로 내려야 한다.
3. **조치 실행은 운영 안전장치 안에 있어야 한다.**
   - 사람 승인, 멱등성, 감사로그, 검증, 실패 시 롤백이 필요하다.
4. **알림은 내부 지표보다 사용자 영향을 우선해야 한다.**
   - 단순히 consumer lag, error rate가 특정 숫자를 넘었다고 바로 page하지 않고, 실제 데이터 처리 지연·누락·실패 같은 사용자 영향 SLI/SLO를 중심에 둬야 한다.

### 현재 Bifrost가 잘하고 있는 부분

현재 RCA 구조는 표준이 권고하는 방향과 상당 부분 맞아 있다.

- RCA는 로그를 그대로 LLM에 던지지 않는다.
- 8계층 35개 root cause 카탈로그로 후보를 제한하고, actionable 32개 root cause에 evidence profile을 붙인다.
- required/supporting/negative 증거 매트릭스로 후보별 근거를 평가하며, 일부 규칙은 `causality_type`·`temporality_required`·`causal_chain_step`을 가진다.
- confidence가 낮으면 `UNKNOWN_WITH_EVIDENCE_GAP`으로 빠진다.
- LLM은 최종 판단자가 아니라 근접 후보의 타이브레이커로만 쓰인다.
- 조치 실행은 정책 게이트, 승인 게이트, 변경 게이트, 멱등성 키, append-only 감사 이력을 거친다.

즉, 현재 구현은 순수 LLM 자동판단 시스템이 아니라 **grounding·abstain·approval 구조를 갖춘 제한형 RCA 시스템**이다.

### 아직 보완해야 할 공격 포인트

문제는 "기본 구조가 있다"와 "운영 수준으로 충분하다"는 다르다는 점이다. 현재 공격받을 만한 포인트는 명확하다.

- 조치 실패 시 자동 롤백 기본 경로가 생겼지만, 현재는 executor 직후 실패/BLOCKED mutation의 inverse tool 기반 롤백이다. 문서상 목표였던 Verifier 실패 후 runbook `rollback_plan` 실행과 보상(saga) 일반화는 별도 검증·확장이 필요하다.
- run record에는 모델 snapshot manifest와 prompt/catalog/evidence matrix/runbook/corpus/eval/code commit 정보가 저장된다. 다만 실제 LLM 호출 경로는 여전히 `gpt-4o` 같은 별칭을 반환하는 `model_for_agent()`를 쓰므로, 실행 모델 자체를 snapshot ID로 강제하는지는 별도 gap이다.
- prompt, catalog, evidence matrix, runbook, corpus, 평가셋 버전은 run reproducibility manifest에 고정된다. 남은 문제는 manifest 조회·재현 절차 검증과 실제 모델 호출 ID의 snapshot 강제 여부다.
- 35건 seed oracle replay의 AC@k/ECE 결과는 보존되어 있지만, resolved incident 기반 정기 평가·캘리브레이션 job은 아직 없다.
- threshold registry/API는 생겼고 이름·값·버전·근거·owner·보정 시각·dataset·rollback 값을 가진다. 다만 RCA scoring과 일부 Spring 설정은 아직 코드 상수·기본값을 직접 참조하므로 registry가 완전한 source of truth는 아니다.
- RCA gold set 저장소, 운영자 피드백 승격, trigger/symptom/root cause/contributing factor 라벨링 guide/API는 생겼다. 남은 문제는 운영 UI·검수 일관성·정기 평가 job이다.
- 알림은 사용자 영향 SLI와 SLO burn-rate 기반 page/ticket/diagnostic routing을 도입했다. 다만 Prometheus 비활성·측정 불가 시 static fallback이 남고, 최종 SLO 수치는 baseline 데이터로 보정해야 한다.
- RCA 결과에서 트리거와 근본원인이 충분히 분리되어 있지 않다.
- 단순 상관 증거와 인과 증거의 차이를 표현하는 evidence rule 필드는 생겼지만, 모든 profile에 일관되게 보강하고 평가로 검증해야 한다.
- 자연어 단순 질의의 depth-aware 짧은 경로는 생겼지만, Router 휴리스틱 품질과 agent/tool 호출량 회귀 테스트가 아직 부족하다.
- run telemetry schema와 stage latency/agent 집계는 생겼다. 다만 tool/LLM/handoff call site instrumentation, 실제 cost_by_stage, budget_used 집계는 아직 부분 구현이다.

### 코드 개선 방향

따라서 다음 단계는 문서상 설명을 더하는 것이 아니라, 실제 코드와 데이터 모델을 보강하는 것이다.

1. **실패 시 되돌릴 수 있게 만든다.**
   - 현재 executor 직후 실패/BLOCKED mutation을 inverse tool로 되돌리는 기본 경로가 있다. Verifier 실패 후 runbook `rollback_plan` 실행과 더 넓은 보상 트랜잭션은 남은 과제로 둔다.
2. **같은 판단을 재현할 수 있게 만든다.**
   - run마다 모델 snapshot manifest, 프롬프트, 카탈로그, evidence matrix, runbook, corpus manifest, 평가셋, 코드 commit을 저장한다. 실제 LLM 호출 모델을 alias가 아닌 snapshot으로 강제하는지는 추가 검증한다.
3. **RCA가 실제로 맞는지 측정한다.**
   - 자체 resolved incident 데이터로 AC@1/AC@3/AC@5/Avg@5를 계산한다.
4. **confidence를 실제 정답률에 맞춘다.**
   - ECE로 과신 구간을 찾고, UNKNOWN 기준과 confidence cap을 조정한다.
5. **임계값을 버전 관리되는 운영 파라미터로 바꾼다.**
   - 임계값마다 이름, 값, 버전, 근거, 평가셋 버전, 마지막 보정 시각, owner, rollback 값을 저장하는 registry를 source of truth로 연결한다.
6. **알림을 사용자 영향 중심으로 바꾼다.**
   - 데이터 신선도, end-to-end latency, 처리 성공률, 데이터 완전성 같은 SLI와 SLO burn-rate 기반 page/ticket routing을 운영 데이터로 보정한다.
7. **원인 지표는 page가 아니라 진단 근거로 재배치한다.**
   - consumer lag, connector FAILED, replication lag 같은 정적 임계값은 사용자 영향이 있으면 page/ticket으로 올리고, 없으면 diagnostic signal 또는 RCA evidence로 낮춘다. static fallback은 측정 불가 상황의 안전망으로만 둔다.
8. **RCA 설명에서 인과와 상관을 구분한다.**
   - `temporality_required=True` 규칙은 시간 선행성이 있을 때만 required evidence로 인정하고, 단순 동시 발생은 supporting으로 낮춘다.
9. **자연어 질의는 필요한 agent만 호출하게 만든다.**
   - 구현된 `execution_depth`와 tool budget 경로를 회귀 테스트로 고정한다. 단순 조회는 capped flat plan으로 direct/single/bounded lookup에서 끝내고, ReAct loop는 `incident_diagnosis`와 `remediation_planning`에서만 켠다.
10. **agent 실행을 관측 가능하게 만든다.**
    - run마다 호출된 agent/tool, 호출 수, 단계별 latency, 실패·재시도·handoff 이유, 비용을 기록한다. 현재는 schema/collector가 있고 실제 tool·LLM·handoff instrumentation은 보강 대상이다.

### 기준을 정하는 방식

이 기준은 우리 마음대로 만든 것이 아니다. 기준틀은 외부 표준과 검증된 사례에서 가져오고, 구체 수치는 Bifrost 운영 데이터로 보정한다.

- 자동 롤백: AWS Well-Architected, Argo Rollouts
- AI 판단 재현성: NIST AI RMF, ISO 42001, MLflow, Langfuse
- RCA 성능 평가: RCAEval의 AC@k/Avg@k
- confidence 검증: Guo et al.의 ECE, PACE-LM
- UNKNOWN 처리: LLM abstention 연구
- SLI/SLO/burn-rate 알림: Google SRE, Datadog
- page/ticket/severity 분류: Google SRE, PagerDuty, ITIL
- agent 호출 제어: OpenAI Agents SDK handoff/guardrail, Anthropic tool-use, Microsoft AutoGen termination, ReAct

요약하면, 현재 Bifrost RCA는 기반은 좋지만 아직 운영 자동화 성숙도가 부족하다. 이 문서의 목적은 그 부족한 지점을 표준 기준으로 식별하고, 실제 구현 작업으로 전환하는 것이다.

## 목차

1. [핵심 결론](#1-핵심-결론)
2. [현재 아키텍처](#2-현재-아키텍처)
3. [리뷰어 도전 질문](#3-리뷰어-도전-질문)
4. [RCA 판별 기준](#4-rca-판별-기준)
5. [표준 지표를 Bifrost에 적용하는 방식](#5-표준-지표를-bifrost에-적용하는-방식)
6. [자연어 질의 Agent 과다 호출 문제](#6-자연어-질의-agent-과다-호출-문제)
7. [개선 로드맵](#7-개선-로드맵)
8. [후속 과제](#8-후속-과제)
9. [인용 시 주의](#9-인용-시-주의)
10. [주요 출처](#10-주요-출처)

---

## 1. 핵심 결론

### 1.1 한 줄 판정

**"로그만 LLM에 던지면 RCA가 되는가"에 대한 표준의 답은 "아니다".**

우리 RCA는 순수 LLM 호출이 아니라 **고정 카탈로그 + required/supporting/negative 증거 매트릭스 + 신뢰도 스코어링 + 증거 부족 시 UNKNOWN 폴백**으로 LLM을 제약한다. 이 구조는 NIST AI 600-1(grounding/인용검증), Self-RAG(증거선별·자기검증), Abstention 서베이(증거부족 시 보류), 에이전트 환각 서베이(지식베이스·규칙 제약)의 권고와 정합한다.

### 1.2 표준 대비 현황

| 구분 | 판정 | 내용 |
|---|---|---|
| 강점 | 충족 | 증거기반 RCA, UNKNOWN abstain, 사람 승인 게이트, 멱등성 키, 완전 감사로그, 카탈로그 버전 추적 |
| 갭 | 개선 필요 | 자동 롤백은 기본 구현됐으나 runbook rollback/saga 일반화가 필요하고, run 재현성 manifest는 저장되나 실제 LLM 호출 모델 alias 강제 해소가 남아 있다. SLI/SLO burn-rate routing은 구현됐지만 baseline 보정·측정 불가 fallback 검증이 필요하다. 트리거와 근본원인 미분리, 상관/인과 증거 구분 전수 보강, resolved incident 기반 정기 캘리브레이션은 계속 gap이다. |

### 1.3 우선 개선 항목

1. **조치 실패 시 자동 롤백**: executor 실패/BLOCKED inverse rollback은 구현됨. Verifier 실패 후 runbook rollback과 보상(saga) 일반화는 보강 필요.
2. **모델 ID 스냅샷 핀고정**: run manifest 저장은 구현됨. 실제 LLM 호출 모델 alias를 snapshot ID로 강제하는 재현성 보강 필요.
3. **알림 SLO화**: SLI/SLO burn-rate routing은 구현됨. baseline 기반 SLO 수치 보정과 fallback 검증 필요.
4. **RCA 설명력 보강**: 트리거와 근본원인 분리, 상관/인과 증거 구분, 신뢰도 캘리브레이션 필요.

---

## 2. 현재 아키텍처

### 2.1 RCA 파이프라인

```text
Router(mode/depth 판정)
  -> Planner(수집 계획)
  -> Retrieval(증거 수집)
  -> Classifier(인시던트 분류)
  -> RCA
  -> Remediation(조치 제안)
  -> Policy/Approval/Change Gate
  -> Executor(실행)
  -> Verifier
  -> Report
```

| 구성요소 | 코드 위치 | 역할 |
|---|---|---|
| RCA 에이전트 | [services/ai-service/app/agents/rca.py](../../services/ai-service/app/agents/rca.py) | `run_rca()` |
| 프롬프트 | [services/ai-service/app/prompts/rca.py](../../services/ai-service/app/prompts/rca.py) | RCA 출력 제약 |
| 근본원인 카탈로그 | [services/ai-service/app/catalogs/root_causes.py](../../services/ai-service/app/catalogs/root_causes.py) | **8계층 35개** `root_cause_id` |
| 증거 매트릭스 | [services/ai-service/app/catalogs/evidence_matrix.py](../../services/ai-service/app/catalogs/evidence_matrix.py) | actionable **32개** evidence profile, required/supporting/negative 증거 규칙 |

**판별 로직 요약**

1. 후보별 required/supporting/negative 증거를 lexical + semantic 매칭한다.
2. confidence를 계산한다.
   - required 전부 충족: 기본 cap 기준 최대 0.88(`0.88 + supporting_ratio * 0.04`로 시작하지만 `RootCause.default_confidence_cap` 기본 0.88에 제한됨)
   - required 부분 충족: `default_confidence_cap`(RootCause 기본 0.88, evidence 미충족 scoring에서는 action-ready가 아닌 낮은 confidence)와 missing evidence로 상한
   - negative 증거: 건당 -0.10
3. 최상위 후보가 `MIN_CONFIDENT_ROOT_CAUSE`(0.60) 미만이면 `UNKNOWN_WITH_EVIDENCE_GAP`로 폴백한다.
4. LLM은 상위 2후보 신뢰도차가 0.10 미만일 때만 타이브레이커로 호출한다.
   - 원본 로그는 전달하지 않는다.
   - 증거 요약만 전달한다.

### 2.2 조치(Remediation)와 안전장치

| 구분 | 코드 위치 | 현재 동작 |
|---|---|---|
| 조치 제안 | [agents/remediation.py](../../services/ai-service/app/agents/remediation.py) | 읽기 전용, runbook에서 제안만 수행 |
| 실행 | [workflow/stages/executor.py](../../services/ai-service/app/workflow/stages/executor.py) | `READY` 상태만 실행, 멱등성 키 주입, `operations-backend` 도구 호출 |
| 게이트 | [policy_guard.py](../../services/ai-service/app/workflow/stages/policy_guard.py), [approval_gate.py](../../services/ai-service/app/workflow/stages/approval_gate.py), [change_gate.py](../../services/ai-service/app/workflow/stages/change_gate.py) | 정책·승인·변경 검증 |
| 감사 | `ExecutionResultOutput`, append-only `StatePatch` | `audit_event_id`, before/after evidence 저장 |

**현재 상태**

- `low/read-only` 조치는 자동 실행한다.
- `medium` 이상은 사람 승인이 필수다.
- executor는 조치 전 `pre_change_snapshot`을 남기고, FAILED/BLOCKED mutation에 대해 inverse tool 기반 자동 롤백을 시도한다. low/medium 원조치의 롤백은 자동 실행하고, high/forbidden 원조치의 롤백은 승인 대기로 남긴다.
- 남은 gap은 Verifier 실패 판정을 기준으로 runbook의 `rollback_plan`을 실행하는 일반 rollback stage와 보상(saga) 범위 확장이다.
- 재시도는 Verifier 루프백 1회(`max_fail_loops=1`)다.

### 2.3 버전 관리

| 항목 | 현재 상태 | 갭 |
|---|---|---|
| 카탈로그 버전 | `catalog_version="0.1.0"` ([core/config.py](../../services/ai-service/app/core/config.py)), run 레코드마다 저장, health API 노출 | 없음 |
| 상태 이력 | `StatePatch operation=VERSION` append-only 이력 | 없음 |
| 모델 매핑 | [llm/model_router.py](../../services/ai-service/app/llm/model_router.py)에서 `AGENT_TIER`(lightweight/analysis) -> `TIER_MODEL_DEFAULT`(gpt-4o-mini/gpt-4o)를 사용하고, run manifest에는 snapshot mapping을 저장한다 | 실제 LLM 호출 모델 선택은 여전히 alias 반환 경로를 사용하므로 snapshot ID 강제 여부 검증 필요 |
| run 재현성 manifest | run마다 `model_id`, `model_tier_map`, prompt hash/version, catalog/evidence matrix/runbook/corpus/eval/code commit, temperature를 저장 | manifest 조회·재현 절차와 실제 호출 모델 snapshot 강제는 추가 검증 필요 |
| 코퍼스 | `corpus/manifest.json`과 manifest hash를 run reproducibility에 저장 | 운영 corpus baseline/version 운영 절차는 보강 필요 |

### 2.4 장애 알림 기준

| 항목 | 현재 기준 |
|---|---|
| 생성 위치 | [operations-backend ... IncidentService.java](../../services/operations-backend/src/main/java/com/bifrost/ops/incident/IncidentService.java) — `onThresholdViolation()` |
| ERROR | SLO burn-rate decision을 우선 적용한다. 사용자 영향이 확인되면 page/CRITICAL, 측정 불가나 fallback이면 ERROR 기준 page/CRITICAL |
| WARN | 동일 리소스 30분 내 2건 이상이면 인시던트 후보가 되며, SLO decision에 따라 ticket/WARNING 또는 diagnostic으로 분기 |
| 에스컬레이션 | 기존 인시던트는 SLO decision severity와 route를 반영하고, CRITICAL 조건이면 상향 |
| 임계값 | `docs/spec.md` 부록 B 기준: consumer lag >= 5,000(WARN) / >= 50,000(CRIT), error rate > 0.5% / > 2%, connector FAILED, replication lag >= 1s / >= 5s, pipeline 생성 5분 초과 등 |
| 주요 갭 | SLI/SLO burn-rate routing은 구현됐지만 Prometheus 비활성·측정 불가 시 static fallback이 남고, 최종 SLO 수치는 baseline 데이터로 보정해야 함 |

### 2.5 코드 기준 미구현·부분 구현 목록

아래 표는 2026-06-22 코드 기준이다. "부분 구현"은 필드·테이블·기초 안전장치는 있으나 이 문서가 목표로 하는 운영 수준의 동작은 아직 없는 상태를 뜻한다.

보안 redaction과 raw evidence store는 이미 일부 구현되어 있다. [evidence/redaction.py](../../services/ai-service/app/evidence/redaction.py), [evidence_repository.py](../../services/ai-service/app/persistence/evidence_repository.py), [state.py](../../services/ai-service/app/schemas/state.py)가 원문 로그·시크릿을 State에 직접 넣지 않는 방향을 갖고 있으므로, 이 문서의 주요 미구현 갭에서는 제외한다.

| 영역 | 현재 코드 상태 | 미구현·부분 구현 갭 | 개선 시 필요한 산출물 |
|---|---|---|---|
| 자연어 질의 실행 깊이 제어 | [RouterOutput](../../services/ai-service/app/schemas/outputs.py)은 `execution_depth`, `max_tool_calls`, `allow_react_loop`, `history_policy`를 가진다. [transitions.py](../../services/ai-service/app/supervisor/transitions.py)는 simple-query lookup depth를 `planner -> retrieval -> report`로 줄이고 Verifier를 제외한다. | Router는 휴리스틱 기반이라 오분류 가능성이 있고, 대표 자연어 질의별 agent/tool 호출량 회귀 테스트가 부족하다. direct_answer도 transition상 planner/retrieval/report stage를 지난다. | depth 분류 테스트, tool budget 회귀 테스트, direct answer fast-path 품질 검증 |
| agent 실행 관측성 | `run_telemetry` schema, collector, state/repository 저장 경로가 있다. stage latency와 called_agents 요약은 저장된다. | 실제 workflow에서 tool/LLM/handoff call site instrumentation이 아직 부분적이다. `cost_by_stage`, `budget_used`는 별도 보강이 필요하다. | tool/LLM/handoff instrumentation, cost/budget 집계, agent/tool count 회귀 테스트 |
| run 단위 재현성 | run reproducibility manifest는 `model_id`, `model_tier_map`, prompt hash/version, catalog/evidence matrix/runbook/corpus/eval/code commit, temperature를 저장한다. [model_router.py](../../services/ai-service/app/llm/model_router.py)는 manifest용 snapshot mapping도 제공한다. | 실제 LLM 호출 모델 선택은 여전히 agent tier별 기본 모델 별칭을 반환하는 경로를 쓴다. manifest 조회·재현 절차 검증도 필요하다. | 실행 모델 snapshot 강제 여부 결정, 재현성 조회 API, 과거 run 재현 리허설 |
| 자동 롤백 | executor는 `pre_change_snapshot`을 남기고, FAILED/BLOCKED mutation에 대해 inverse tool 기반 자동 롤백을 실행한다. 결과는 rollback status/audit event/state patch로 남는다. | 현재 구현은 executor 직후 상태 기반 inverse rollback이다. Verifier 실패 후 runbook `rollback_plan` 실행과 보상(saga) 일반화는 남아 있다. | runbook rollback stage, risk-tiered rollback policy 확장, rollback 결과 검증·감사로그 |
| threshold governance | threshold registry/API는 `threshold_name`, `value`, `version`, `basis`, `owner`, `last_calibrated_at`, `dataset_version`, `rollback_value`를 가진다. RCA/Spring 주요 threshold 초기값도 등록되어 있다. | RCA scoring은 여전히 [rca.py](../../services/ai-service/app/agents/rca.py)의 `MIN_CONFIDENT_ROOT_CAUSE=0.60`, `LLM_TIE_MARGIN=0.10`과 [types.py](../../services/ai-service/app/catalogs/types.py)의 기본값을 직접 참조한다. registry가 모든 runtime threshold의 source of truth는 아니다. | runtime threshold registry 연결, calibration report linkage, 변경 이력 기반 rollback 운영 |
| RCA gold set·라벨링 | gold set schema/API, labeling guide, 운영자 feedback 저장, ai-service gold set 승격 경로가 있다. `accepted_root_cause_id`, `trigger`, `symptom`, `contributing_factors`, `human_verdict`가 모델에 포함된다. | 운영 UI, inter-review consistency, resolved incident 기반 정기 scheduled eval job/monthly calibration artifact는 남아 있다. | 운영자 검수 UI/API 보강, 라벨링 가이드 운영, 정기 eval/calibration job |
| AC@k·ECE 평가 리포트 | [scripts/rca_eval_campaign.py](../../services/ai-service/scripts/rca_eval_campaign.py)로 seed oracle incident-type replay를 재실행할 수 있다. 과거 JSON 증적 문서는 1차 문서 정리에서 제거했다. | resolved incident 기반 정기 평가 job, monthly calibration report, threshold recommendation artifact는 아직 없다. | scheduled eval job, monthly calibration report, threshold recommendation artifact |
| online feedback·drift 감시 | online feedback event table/API와 drift report가 있다. accept/modify/override, confidence PSI, UNKNOWN ratio, root cause concentration, override ratio를 계산한다. | dashboard, 운영 기준 확정, 자동 threshold 재보정 연결은 남아 있다. | drift dashboard, threshold 재보정 trigger 운영화 |
| 사용자 영향 SLI/SLO | `good_event/total_event` 기반 freshness, end-to-end latency, success, completeness, provisioning SLI 정의와 측정 API가 있다. SLO burn-rate service는 page/ticket/diagnostic routing과 severity_reason을 산출하고 [IncidentService.java](../../services/operations-backend/src/main/java/com/bifrost/ops/incident/IncidentService.java)에 반영된다. | Prometheus 비활성·측정 불가 시 UNKNOWN/static fallback이 남고, 최종 SLO 수치와 low-traffic 보정은 운영 baseline으로 확정해야 한다. | baseline 수집, SLO config 보정, fallback/라우팅 회귀 테스트 |
| 인과/상관 증거 태그 | [EvidenceRule](../../services/ai-service/app/catalogs/types.py)은 `kind`, `semantic_allowed`, `causality_type`, `temporality_required`, `causal_chain_step`을 가진다. [rca.py](../../services/ai-service/app/agents/rca.py)는 시간 선행성이 없는 `temporality_required` required match를 supporting으로 강등한다. | 현재 temporal rule은 일부 profile에만 적용되어 있고, 전체 evidence matrix의 인과/상관 태그 일관성과 평가 기반 confidence 보정이 아직 부족하다. | evidence rule 태그 전수 보강, RCA scoring/eval fixture 확장, 인과 사슬 explanation 검증 |
| KEDB형 운영 지식화 | KEDB schema/API/repository와 static KEDB report surface가 있다. root cause별 owner, known symptoms, verified fixes, rollback procedure, recurrence count, last_seen, incident links를 저장할 수 있다. | RCA 결과에서 recurrence/incident links를 자동 누적하는 경로는 별도 검증이 필요하다. | RCA 결과와 runbook·owner·재발 이력 자동 연결 |

---

## 3. 리뷰어 도전 질문

### Q1. 환각 통제: "로그만 던지면 RCA가 되나?"

**답: 표준상 "아니다". 우리는 이미 순수 LLM이 아니다.**

| 표준/논문 | 핵심 내용 | 우리 매핑 |
|---|---|---|
| [NIST AI 600-1](https://nvlpubs.nist.gov/nistpubs/ai/NIST.AI.600-1.pdf) | 환각은 "통계적 다음 토큰 예측의 본질적 산물". 완화책: grounding/RAG(MS-2.5-005), 출처·인용 검증(MS-2.5-003), known ground truth 대비(MP-2.3-001) | 증거 매트릭스 = grounding, explanation의 `evidence_id` 인용 |
| [Self-RAG, ICLR'24](https://arxiv.org/abs/2310.11511) | 무차별 고정 개수 문서 삽입은 성능 저하. relevance 선별 + ISSUP 자기검증 필요 | required/supporting/negative 선별 |
| [Abstention 서베이, TACL'25](https://direct.mit.edu/tacl/article/doi/10.1162/tacl_a_00754/131566/Know-Your-Limits-A-Survey-of-Abstention-in-Large) | 증거 불충분·불확실·지식충돌 시 보류가 환각완화·안전성 전략 | `UNKNOWN_WITH_EVIDENCE_GAP` 폴백 |
| [에이전트 환각 서베이](https://arxiv.org/html/2509.18970v1) | naive log-to-LLM은 불충분하며, 지식베이스·규칙·제약 프롬프팅이 완화책 | 카탈로그 35개 ID 강제 + 스키마 강제 |
| [PACE-LM, Microsoft, FSE'24](https://arxiv.org/pdf/2309.05833) | LLM 단독 RCA는 낮은 성능(GPT-4 43.4%)을 보이며, 캘리브레이션된 신뢰도로 채택/보류 필요 | confidence cap + abstain |

**답변 한 줄**

> 로그를 LLM에 던지지 않는다. NIST·Self-RAG·Abstention이 권고하는 grounding·증거선별·증거부족 시 보류를 카탈로그 + 증거 매트릭스 + UNKNOWN으로 구현했다.

### Q2. 롤백 / 실패 시나리오

**판정: 부분 충족**

| 표준 | 핵심 내용 | 우리 상태 |
|---|---|---|
| [AWS Well-Architected OPS06-BP04](https://docs.aws.amazon.com/wellarchitected/latest/operational-excellence-pillar/ops_mit_deploy_risks_auto_testing_and_rollback.html) | 목표 미달성·테스트 실패 등 사전정의 조건에서 롤백이 수동개입 없이 자동 트리거되어야 하며, 수동 단계는 안티패턴 | executor 실패/BLOCKED inverse rollback 기본 구현. Verifier 실패 후 runbook rollback 일반화는 보강 필요 |
| [Google SRE — Automation](https://sre.google/sre-book/automation-at-google/) | 자동화 5단계 성숙도, 멱등성, blast-radius 제한(rate limiting), Diskerase 교훈(sanity check) | 멱등성 키 있음. rate limit·sanity check는 보강 여지 |
| [NIST AI 100-1](https://nvlpubs.nist.gov/nistpubs/ai/nist.ai.100-1.pdf) | fail safely(MEASURE 2.6), supersede/disengage/deactivate(MANAGE 2.4), rollover/fallback | 승인 게이트·UNKNOWN 정지 |

**답변 한 줄**

> 승인 게이트·멱등성·감사로그에 더해 executor 실패/BLOCKED 조치의 inverse 자동 롤백은 구현했다. 단, AWS가 권고하는 넓은 의미의 "검증 실패 시 자동 롤백"을 완전히 충족하려면 Verifier 실패 후 runbook rollback과 보상(saga) 범위를 더 넓혀야 한다.

### Q3. 버전 관리

| 표준 | 핵심 내용 | 우리 상태 |
|---|---|---|
| [ISO/IEC 42001:2023](https://www.iso.org/standard/42001) | 인증 가능 AIMS, 리스크 기반 라이프사이클·변경관리 | run reproducibility manifest에 모델 snapshot map·프롬프트·카탈로그·평가셋·코드 commit을 저장. 실제 LLM 호출 모델 snapshot 강제와 재현 절차 검증은 보강 필요 |
| [NIST AI 100-1](https://nvlpubs.nist.gov/nistpubs/ai/nist.ai.100-1.pdf) | 정확도 측정은 대표 테스트셋 + 문서화된 방법론과 짝을 이루어야 하며, 일반화 한계 문서화(MEASURE 2.5)가 필요 | 평가셋·캘리브레이션 미비 |

**답변 한 줄**

> run manifest에는 카탈로그·상태·모델 snapshot map·프롬프트·평가셋·코드 commit이 저장된다. 단, 실제 LLM 호출은 아직 alias 기반 모델 선택 경로를 쓰므로, 실행 모델까지 날짜 스냅샷으로 강제하는 재현성 보강이 필요하다.

### Q4. 장애 알림 기준

#### 현재 현황

현재 알림은 §2.4처럼 사용자 영향 SLI/SLO burn-rate decision을 우선 적용하고, 측정 불가나 Prometheus 비활성 상황에서는 정적 임계값 기반 fallback을 사용한다. connector failed, consumer lag, replication lag 같은 **원인 기반(cause-based)** 신호는 사용자 영향이 없으면 diagnostic signal로 낮추고, 사용자 영향 SLO가 깨질 때 page/ticket으로 올린다.

#### 표준 근거

| 주제 | 핵심 내용 | 출처 |
|---|---|---|
| 증상 vs 원인 | "what's broken"은 증상, "why"는 원인이다. 원인보다 증상 포착에 더 많은 노력을 쓰는 편이 낫다. | [SRE Book — Monitoring](https://sre.google/sre-book/monitoring-distributed-systems/) |
| 알림 가능성/피로 | 모든 page는 actionable해야 하며, 효과적인 알림 시스템은 신호가 좋고 노이즈가 낮아야 한다. 잦은 page는 무시·마스킹을 유발한다. | 동일 |
| 4 golden signals | latency, traffic, errors, saturation | 동일 |
| error budget burn-rate | burn rate는 SLO 대비 error budget을 얼마나 빠르게 소비하는지다. | [SRE Workbook — Alerting on SLOs](https://sre.google/workbook/alerting-on-slos/) |
| 멀티윈도우 멀티-burn-rate | 99.9% SLO 권장: Page 1h/5m @14.4x(2% 소진), Page 6h/30m @6x(5%), Ticket 3d/6h @1x(10%). short window는 long window의 1/12 기간을 권장. | 동일 |
| 알림 전략 평가 4지표 | precision, recall, detection time, reset time | 동일 |
| 심각도 분류 | ITIL: Priority = Impact x Urgency -> P1~P4(Impact 4단계 x Urgency 3단계). PagerDuty: SEV-1..N(낮을수록 심각), SEV-3 초과는 major incident, 불확실하면 더 높게 분류 | [Atlassian/ITIL](https://www.atlassian.com/itsm/problem-management/process), [PagerDuty Severity](https://response.pagerduty.com/before/severity_levels/) |

#### 권고/갭

우리 알림은 사용자 영향 SLI/SLO burn-rate routing을 도입했지만, 아직 운영 baseline으로 SLO 수치를 확정하지 않았고 Prometheus 비활성·측정 불가 시 static fallback이 남는다.

1. 파이프라인의 사용자 영향 SLI(예: end-to-end 데이터 신선도/지연)는 정의·측정 API를 갖췄다.
2. burn-rate(멀티윈도우) page/ticket rule을 추가했고 IncidentService의 severity/route/reason에 반영한다.
3. 현 정적 임계값(lag/error rate)은 사용자 영향이 없으면 diagnostic signal로 낮춘다. 다만 fallback 경로가 기대대로 제한되는지 회귀 테스트가 필요하다.
4. severity는 SLO burn-rate와 affected resource count를 포함한 `severity_reason`을 남긴다. 최종 impact/urgency 수치화는 운영 baseline으로 보정한다.

**답변 한 줄**

> 사용자 영향 SLI 기반 burn-rate 알림을 상위에 두는 기본 경로는 구현됐다. 다음 단계는 운영 baseline으로 SLO 수치를 보정하고, 측정 불가 fallback과 low-traffic pipeline의 precision/recall을 검증하는 것이다.

---

## 4. RCA 판별 기준

### 4.1 방법론

#### 핵심 원칙

- **단일 RCA 기법 표준은 없다.**
  - Google SRE는 팀마다 다양한 기법을 사용하고, 상황에 맞는 기법을 선택한다고 설명한다.
  - 인시던트는 복수 기여원인(contributing root cause(s))으로 볼 수 있다.
  - 따라서 우리의 **상위 N개 후보 랭킹 출력**은 산업 프레이밍에 부합한다.
  - 출처: [SRE Book — Postmortem Culture](https://sre.google/sre-book/postmortem-culture/)
- **인과와 상관은 구분해야 한다.**
  - 인과추론 RCA는 3계열로 정리된다: PC algorithm, regression Granger, graphical event models.
  - CausalRCA는 gradient-based 인과그래프를 사용한다.
  - 출처: [IBM CODS 2021](https://dl.acm.org/doi/fullHtml/10.1145/3430984.3431027), [CausalRCA](https://arxiv.org/pdf/2209.02500)
- **단일 알고리즘만으로는 불충분하다.**
  - 9개 causal discovery + 21개 RCA 평가에서 모든 상황에 우월한 방법은 없었다.
  - 합성 데이터 성능이 실제 시스템 성능을 보장하지 않는다.
  - 따라서 룰 + 인과 하이브리드, 자체 실데이터 검증, UNKNOWN 정당화가 필요하다.
  - 출처: [ASE'24](https://arxiv.org/abs/2408.13729)

### 4.2 인과주장 검증 기준: 상관 ≠ 인과

RCA에서 "근본 원인"으로 지정하려면 단순 상관을 넘어 인과 근거가 필요하다. 두 권위 표준이 검증 체크리스트를 제공한다.

| 기준 | RCA 적용 포인트 | 출처 |
|---|---|---|
| Bradford Hill 기준 | 9원칙 중 RCA에 직접 쓰이는 것은 Temporality(원인이 결과에 선행, 필수 조건), Strength(연관 강도), Consistency(여러 신호·사례에서 반복 관측), Specificity, Plausibility다. 핵심은 통계적 연관만으로 인과를 추론하면 안 된다는 점이다. | [Bradford Hill criteria](https://en.wikipedia.org/wiki/Bradford_Hill_criteria) |
| Pearl의 인과 사다리 | ① Association(seeing, 상관) ② Intervention(doing) ③ Counterfactual(imagining). 순수 통계적 머신러닝 시스템은 1단계(association)에 머문다. 즉 상관 신호만으로는 인과 주장이 불가하다. | [Bareinboim et al., On Pearl's Hierarchy](https://philpapers.org/rec/BAROPH-2) |
| RCA 검증 실무 | 식별된 근본원인에서 관측 증상까지 인과 사슬을 순방향으로 추적하고, 여러 신호에서 일관되게 성립하면 확신을 상향한다. | 실무 적용 기준 |

#### 우리 적용

evidence matrix를 인과 사다리에 매핑한다.

1. **Temporality를 인과 증거의 필수 게이트**로 둔다.
   - 예: change/배포 이벤트 타임스탬프가 증상 발생에 선행할 때만 인과 증거로 인정한다.
2. 단순 동시 발생(co-occurrence)은 rung-1 association으로 보고 `supporting`까지만 인정한다.
3. 현재 `negative` 증거 규칙은 대안 원인의 시그니처가 있으면 후보를 약화하므로, 조잡한 counterfactual 검사에 해당한다. 이를 명시적으로 강화한다.
4. required 증거를 "근본원인 -> 증상" 인과 사슬 순서로 구성하고, 사슬 일관성으로 confidence를 산정한다.

### 4.3 택소노미

| 주제 | 내용 | 시사점 |
|---|---|---|
| change 우선 의심 | Google 2010~2017 기준 binary push 37% + config push 31% = 68%가 1·2위 트리거 | 직전 변경 이벤트를 강한 supporting으로 활용 |
| 트리거와 근본원인 분리 | Google 표준 템플릿은 둘을 별도 항목으로 기록해 트렌드 분석 | RCA 모델과 인시던트 기록에서 `trigger` 메타데이터 분리 필요 |
| ITIL Known Error / KEDB | root cause + 워크어라운드 문서화 후 DB화 | 카탈로그를 KEDB 포맷으로 확장 |
| 이력 NLP 마이닝 | 과거 인시던트 이력에서 카탈로그 보강 가능 | 누락 카테고리 탐지에 활용 |
| RCAEval 벤치마크 | fault 11종(resource 4/network 2/code-level 5) | 우리 `data_quality` 계층은 외부 표준에 부재하는 고유 강점 |

출처: [SRE Workbook](https://sre.google/workbook/postmortem-analysis/), [Atlassian](https://www.atlassian.com/itsm/problem-management/process), [Saha & Hoi](https://arxiv.org/abs/2204.11598), [RCAEval](https://arxiv.org/html/2412.17015v1)

### 4.4 증거·신뢰도·평가

| 항목 | 내용 |
|---|---|
| 평가 지표 | AC@k + Avg@k(MAP/MRR 아님) |
| 현 SOTA 중간 수준 | Avg@5 0.46~0.54, CausalRCA AC@3 0.719 |
| 권고 | confidence-gated/abstain |
| 우리 적용 | confidence를 캘리브레이션(ECE) 대상으로 삼고, abstain 임계값은 신뢰도=정확도 일치점으로 조정 |

출처: [RCAEval](https://arxiv.org/pdf/2412.17015), [CausalRCA](https://arxiv.org/pdf/2209.02500), [PACE-LM](https://arxiv.org/pdf/2309.05833)

---

## 5. 표준 지표를 Bifrost에 적용하는 방식

### 5.1 적용 원칙

외부 표준과 글로벌 벤더 문서는 보통 "이 숫자를 그대로 쓰라"고 말하지 않는다. 대신 **어떤 지표를 봐야 하는지**, **어떤 절차로 임계값을 정해야 하는지**, **어떤 알림은 page이고 어떤 알림은 ticket인지**를 제시한다. 따라서 Bifrost는 아래 원칙을 따른다.

1. **기준틀은 외부 표준에서 가져온다.**
   - 자동 롤백: AWS Well-Architected, Argo Rollouts
   - AI 재현성/버전관리: NIST AI RMF, ISO 42001, MLflow, Langfuse
   - confidence/ECE: Guo et al., PACE-LM
   - RCA 랭킹 평가: RCAEval
   - UNKNOWN/abstain: Abstention 연구
   - SLI/SLO/burn-rate 알림: Google SRE, Datadog
   - page/ticket/severity: Google SRE, PagerDuty, ITIL
2. **구체 수치는 Bifrost 운영 데이터로 보정한다.**
   - 예: `MIN_CONFIDENT_ROOT_CAUSE=0.60`은 초기값일 뿐이다. resolved incident 데이터로 confidence 구간별 실제 정답률을 본 뒤 조정한다.
   - 예: Google SRE의 99.9% SLO와 14.4x/6x burn-rate는 좋은 시작점이지만, Bifrost의 데이터 파이프라인 트래픽·지연 허용치·운영 인력 상황에 맞게 보정한다.
3. **구현 작업에서는 "임의 기준"이 아니라 "검증 가능한 도입 방식"을 따른다.**
   - "이 수치가 절대 표준이다"가 아니라 "이 지표와 절차가 업계 표준이며, 수치는 자체 데이터로 캘리브레이션한다"가 구현 기준이다.
   - 발표에는 이 기준과 실제 코드 개선 결과를 함께 반영한다.

### 5.2 개선 항목별 외부 근거와 Bifrost 적용

| 개선 항목 | 참고 기준 | Bifrost 적용 방식 | 산출물/완료 조건 |
|---|---|---|---|
| 조치 실패 시 자동 롤백 | [AWS OPS06-BP04](https://docs.aws.amazon.com/wellarchitected/latest/operational-excellence-pillar/ops_mit_deploy_risks_auto_testing_and_rollback.html), [Argo Rollouts AnalysisRun](https://argo-rollouts.readthedocs.io/en/stable/features/analysis/) | Executor가 조치 전 상태를 저장하고, FAILED/BLOCKED mutation은 inverse tool로 자동 롤백한다. `low/read-only`와 일부 `medium` 조치는 자동화하고, `high` 위험 조치는 rollback 실행도 승인 대상으로 둔다. | `pre_change_snapshot`, `rollback_action_id`, `rollback_status`, `rollback_audit_event_id`가 run 결과에 남는다. 남은 조건: Verifier 실패 후 runbook `rollback_plan` 실행과 보상(saga) 일반화. |
| run 단위 버전 고정 | [NIST AI RMF 1.0](https://nvlpubs.nist.gov/nistpubs/ai/nist.ai.100-1.pdf), [ISO/IEC 42001](https://www.iso.org/standard/42001), [MLflow Model Registry](https://mlflow.org/docs/latest/ml/model-registry/), [Langfuse Prompt Management](https://langfuse.com/docs/prompt-management/get-started) | RCA run마다 모델 snapshot map, 프롬프트, 카탈로그, evidence matrix, runbook, corpus manifest, 평가셋, 코드 commit을 저장한다. | `run_id`로 당시 manifest를 조회할 수 있다. 남은 조건: 실제 LLM 호출 모델이 alias가 아니라 snapshot/provider revision으로 강제되는지 검증하고, 재현성 조회 API와 재현 리허설을 마련한다. |
| confidence 캘리브레이션(ECE) | [On Calibration of Modern Neural Networks](https://arxiv.org/abs/1706.04599), [PACE-LM](https://arxiv.org/abs/2309.05833) | resolved incident를 모아 confidence 구간별 실제 정답률을 측정한다. confidence 0.8 구간이 실제로 80% 전후로 맞는지 확인하고, 과신 구간은 confidence cap 또는 UNKNOWN 기준 상향으로 보정한다. | 월 1회 calibration report 생성. 구간별 `count`, `avg_confidence`, `accuracy`, `gap`, `ECE`를 기록한다. |
| AC@k / Avg@k RCA 평가 | [RCAEval](https://arxiv.org/html/2412.17015v1) | RCA가 단일 정답만 맞히는지 보지 않고, 상위 후보 랭킹 안에 정답이 들어오는지 본다. Bifrost resolved incident마다 `accepted_root_cause_id`와 후보 랭킹을 저장한다. | `AC@1`, `AC@3`, `AC@5`, `Avg@5`를 root cause 계층별로 산출한다. `data_quality`, `connector`, `schema`, `infra`, `change` 계층별 약점을 볼 수 있다. |
| UNKNOWN 기준 조정 | [Know Your Limits: Abstention Survey](https://arxiv.org/abs/2407.18418), [PACE-LM](https://arxiv.org/abs/2309.05833) | `UNKNOWN_WITH_EVIDENCE_GAP`은 실패가 아니라 안전장치로 둔다. confidence threshold와 required evidence completeness를 함께 본다. UNKNOWN일 때는 "추가로 필요한 증거"를 명시한다. | UNKNOWN 비율, UNKNOWN 중 실제 오답 회피율, UNKNOWN 후 추가 수집으로 원인 확정된 비율을 추적한다. |
| 사용자 영향 SLI 정의 | [Google SRE Workbook — Alerting on SLOs](https://sre.google/workbook/alerting-on-slos/), [Google SRE Book — Monitoring](https://sre.google/sre-book/monitoring-distributed-systems/) | 내부 Kafka 지표보다 사용자 관점의 데이터 파이프라인 품질을 상위 지표로 둔다. 데이터 신선도, end-to-end latency, 처리 성공률, 데이터 완전성, provisioning 성공률을 `good_event / total_event`로 정의하고 측정 API를 제공한다. | 기본 SLI 정의·측정 API는 구현됨. 남은 조건: baseline 데이터로 최종 objective와 low-traffic 보정 기준을 확정한다. |
| SLO burn-rate page 알림 | [Google SRE Workbook — Alerting on SLOs](https://sre.google/workbook/alerting-on-slos/), [Datadog Burn Rate Alerts](https://docs.datadoghq.com/service_level_objectives/burn_rate/) | page는 consumer lag 같은 원인 지표가 아니라 사용자 영향 SLO가 빠르게 깨질 때 발생한다. Google SRE의 시작값을 기준으로 `1h/5m @14.4x`, `6h/30m @6x`는 page, `3d/6h @1x`는 ticket 후보로 구현했다. | `IncidentSeverity=CRITICAL`은 SLO burn-rate page 조건과 연결된다. 남은 조건: 측정 불가 fallback과 low-traffic pipeline precision/recall/detection/reset time 검증. |
| 정적 임계값 재분류 | [Google SRE Book — Monitoring](https://sre.google/sre-book/monitoring-distributed-systems/), [PagerDuty Severity Levels](https://response.pagerduty.com/before/severity_levels/) | 기존 consumer lag, connector FAILED, replication lag, error rate 임계값은 버리지 않는다. 다만 사용자 영향이 없으면 page가 아니라 diagnostic signal 또는 RCA evidence로 낮춘다. | 알림 라우팅이 `page`, `ticket`, `diagnostic`으로 분리된다. 원인 지표는 RCA evidence로 남고, page는 사용자 영향 SLO 위반에 집중된다. |
| severity 산정 | [Atlassian/ITIL Problem Management](https://www.atlassian.com/itsm/problem-management/process), [PagerDuty Severity Levels](https://response.pagerduty.com/before/severity_levels/) | ERROR/WARN 단일축을 SLO burn-rate 기반 Impact x Urgency로 보강한다. 영향 범위와 긴급도(error budget 소진 속도, 복구 가능 시간)를 함께 본다. | `severity_reason`에 impact, urgency, SLO burn-rate, affected_resource_count가 남는다. baseline 기반 severity threshold 보정은 남아 있다. |

### 5.3 Bifrost용 후보 SLI/SLO 정의

아래 값은 최종 확정 수치가 아니라 **운영 데이터로 보정하기 전의 설계 후보**다. 현재는 SLI 정의와 초기 측정·routing API가 구현되어 있으므로, 다음 단계는 2~4주 baseline 데이터로 SLO 수치를 확정하고 발표에서는 실제 수집한 baseline, 선택한 SLO, 알림 라우팅 결과를 함께 설명하는 것이다.

| SLI | good event 정의 | bad event 예시 | 관련 원인 지표 |
|---|---|---|---|
| 데이터 신선도 | source 이벤트가 `freshness_objective_minutes` 안에 sink에 반영됨 | sink 반영 지연, consumer lag 장기 증가 | consumer lag, connector throughput, sink write latency |
| end-to-end latency | source timestamp -> sink committed timestamp가 `latency_objective_ms` 이하 | 처리 지연, connector backpressure | connector task 상태, broker latency, replication lag |
| 처리 성공률 | 전체 이벤트 중 정상 처리·적재 성공 | connector error, DLQ 증가, retry exhaustion | error rate, DLQ count, connector FAILED |
| 데이터 완전성 | 기대 row/event 수와 sink 반영 수가 허용 오차 내 일치 | 누락, 중복, schema reject | schema mismatch, sink constraint error |
| provisioning 성공률 | pipeline 생성 요청이 허용 시간 내 성공 상태로 전환 | pipeline 생성 5분 초과, connector 생성 실패 | provisioning event, Kubernetes/Kafka apply error |

### 5.4 page/ticket/diagnostic 라우팅 기준

| 신호 | 현재 처리 | 남은 보정 |
|---|---|---|
| 사용자 영향 SLO burn-rate page 조건 충족 | `CRITICAL` incident + page route | baseline으로 objective와 burn-rate threshold 보정 |
| 사용자 영향 SLO burn-rate ticket 조건 충족 | `WARNING` incident + ticket route | low-traffic pipeline의 precision/recall 검증 |
| consumer lag WARN/CRIT | SLO 영향이 있으면 page/ticket, 영향이 없으면 diagnostic signal. 측정 불가 시 static fallback | fallback이 page noise를 만들지 않는지 검증 |
| connector FAILED | affected pipeline의 SLI 악화가 있으면 page/ticket, 없으면 diagnostic/RCA evidence | connector-only signal의 ticket/diagnostic 기준 보정 |
| replication lag | 데이터 신선도 SLI에 영향 있으면 ticket/page, 단기 회복이면 diagnostic evidence | freshness objective baseline 확정 |
| pipeline 생성 5분 초과 | provisioning SLI 기준으로 ticket/page 후보. SLI 측정 불가 시 static fallback | provisioning objective baseline 확정 |

### 5.5 구현·발표 반영 문장

> 본 개선 작업은 임의 기준이 아니다. 자동 롤백은 AWS Well-Architected와 Argo Rollouts의 failure-condition 기반 rollback 패턴을 코드에 반영한다. AI 판단 재현성은 NIST AI RMF, ISO 42001, MLflow, Langfuse의 versioning·lineage 원칙을 run record에 반영한다. RCA 성능은 RCAEval의 AC@k/Avg@k 지표로 평가하고, confidence는 Guo et al.의 ECE와 PACE-LM의 cloud incident RCA confidence estimation을 기준으로 캘리브레이션한다. 알림 체계는 Google SRE와 Datadog의 SLO burn-rate alerting, PagerDuty/ITIL의 severity 분류를 반영한다. 단, Bifrost의 구체 SLO와 UNKNOWN 임계값은 자체 인시던트 데이터로 캘리브레이션한다.

---

## 6. 자연어 질의 Agent 호출량 제어

### 6.1 문제 정의

사용자가 자연어로 "상태 한번 봐줘", "지금 잘 돌아가?", "lag 확인해줘"처럼 묻는 경우, 실제 의도보다 많은 agent와 tool이 호출될 수 있다. 이 문제는 단순히 LLM이 말을 많이 해서 생기는 문제가 아니라, workflow가 자연어 질의를 최소 실행 경로로 줄이고 tool budget을 지키는지의 문제다.

현재 코드는 `execution_depth`와 depth별 tool budget을 도입해 과다 호출 문제의 1차 구조를 해결했다. 단순 조회 depth는 Verifier를 건너뛰고 `planner -> retrieval -> report`로 끝난다. 남은 문제는 Router 휴리스틱이 의도를 잘 분류하는지, Planner/ReAct가 budget을 실제로 넘지 않는지, 대표 질의별 호출량이 테스트로 고정되어 있는지다.

서비스 관점에서 문제는 세 가지다.

1. Router 휴리스틱이 단순 조회를 인시던트 분석으로 오분류하면 여전히 무거운 stage를 탈 수 있다.
2. Retrieval/ReAct가 허용된 `max_tool_calls`와 `allow_react_loop`를 모든 경로에서 지키는지 회귀 테스트가 필요하다.
3. 같은 질문에 대화 히스토리가 붙으면서 Router/Planner가 이전 장애 맥락까지 보고 더 무거운 mode/tool을 고를 수 있다.

### 6.2 현재 코드 기준 상태

| 항목 | 코드 근거 | 현재 상태와 남은 영향 |
|---|---|---|
| `simple_query` depth-aware 경로 | [transitions.py](../../services/ai-service/app/supervisor/transitions.py)의 `SIMPLE_QUERY_LOOKUP_STAGES = ("planner", "retrieval", "report")`와 `_LOOKUP_DEPTHS` | 단순 조회는 Verifier를 건너뛰지만, direct answer도 stage상 planner/retrieval/report를 지난다. |
| Router가 실행 깊이를 고른다 | [agents/router.py](../../services/ai-service/app/agents/router.py)는 `_classify_depth()`로 `execution_depth`를 정하고 `depth_budget()` 결과를 `RouteDecision`에 넣는다 | 구현은 휴리스틱 기반이므로 오분류 회귀 테스트가 필요하다. |
| Planner prompt가 최소 충분 조회를 권장한다 | [prompts/planner.py](../../services/ai-service/app/prompts/planner.py)의 규칙: 기본은 가장 좁은 tool 1개이며, 단순 조회·현황 질의는 1~2개로 끝낸다 | prompt는 이미 보수적이다. 남은 리스크는 Router 오분류와 대표 자연어 질의별 호출량 회귀 테스트 부재다. |
| Retrieval/ReAct budget | [agents/retrieval.py](../../services/ai-service/app/agents/retrieval.py)는 `max_tool_calls==0`이면 운영 tool 호출을 건너뛰고, `allow_react_loop`가 켜진 depth에서만 ReAct를 돈다 | budget 집행은 구현됐지만 대표 자연어 질의별 호출량 테스트가 필요하다. |
| 대화 히스토리 제한 | [workflow/runner.py](../../services/ai-service/app/workflow/runner.py)는 depth budget의 `history_policy`를 사용한다 | `HistoryPolicy.NONE` 경로는 생겼지만, summary/full 정책의 오염 방지 품질은 별도 검증이 필요하다. |
| Verifier 제외 | simple-query lookup depth는 `SIMPLE_QUERY_LOOKUP_STAGES`를 타므로 Verifier를 포함하지 않는다 | 운영 변경·RCA 결론 경로에서는 Verifier가 유지된다. |
| 설계 문서와 현재 구현 설명이 일부 불일치한다 | [contract-agent-roles.md](./backend-fastapi/contract/contract-agent-roles.md)는 Retrieval이 depends_on 순차 chain을 아직 해석하지 않는다고 쓰지만, 현재 [retrieval.py](../../services/ai-service/app/agents/retrieval.py)는 `_run_plan_steps(...)`에서 `depends_on` wave 실행을 구현한다 | 문서가 실제 agent 구조 검증의 기준으로 쓰이기 어렵다. 문서와 코드 동기화가 필요하다. |

### 6.3 외부 기준에서 본 올바른 방향

| 참고 기준 | 핵심 방식 | Bifrost 적용 |
|---|---|---|
| [OpenAI Agents SDK — Handoffs](https://openai.github.io/openai-agents-python/handoffs/) | specialist agent가 있을 때 handoff로 특정 agent에만 위임하고, handoff input/filter로 다음 agent가 볼 컨텍스트를 제한한다 | Router가 모든 stage를 정적으로 여는 대신, `execution_depth`와 `next_agent`를 명시해 필요한 specialist만 호출해야 한다. 이전 대화 전체를 넘기지 말고 agent별 input filter를 둔다. |
| [OpenAI Agents SDK — Guardrails](https://openai.github.io/openai-agents-python/guardrails/) | expensive model/tool 실행 전에 blocking guardrail을 둬 불필요한 비용·tool 실행을 막는다 | Router 앞 또는 Router 직후에 deterministic `query_scope_guard`를 두고, 지식 질의/단일 조회/인시던트 분석/조치 실행을 비용순으로 차단한다. |
| [Anthropic Tool Use](https://platform.claude.com/docs/en/agents-and-tools/tool-use/overview) | tool trigger boundary는 prompt와 `tool_choice`로 조절한다. "Use your judgment..."는 보수적 tool 사용을 유도한다 | Planner/ReAct prompt를 "필요한 만큼"에서 "최소 충분 tool"로 바꾸고, simple_query는 tool_choice none/auto/forced를 intent별로 분리한다. |
| [AutoGen Termination](https://microsoft.github.io/autogen/stable/user-guide/agentchat-user-guide/tutorial/termination.html) | multi-agent run은 무한히 이어질 수 있으므로 message/token/timeout/handoff 등 termination condition이 필요하다 | 현재 step/loop guard는 있지만, simple_query 전용 `max_agents=2`, `max_tool_calls=1~2`, `no_tool_direct_answer` 같은 intent별 termination이 필요하다. |
| [ReAct](https://arxiv.org/abs/2210.03629) | reasoning과 acting을 interleave해 외부 지식을 쓰되, action은 필요한 정보를 얻기 위한 수단이다 | Bifrost의 ReAct 루프는 incident_diagnosis/remediation_planning처럼 chaining과 진단 깊이가 필요한 경로에만 켠다. simple_query의 식별자 조회는 capped flat plan으로 처리하고, 지식/RAG 질의는 단락한다. |

### 6.4 해결 방향과 현재 구현 상태

#### 6.4.1 Router 출력의 `execution_depth`

현재 Router는 mode와 함께 다음 필드를 반환한다. 외부 mock이나 구버전 출력에서 depth가 비어 있으면 runner가 mode 기준 기본 depth로 보정한다.

| 필드 | 값 | 의미 |
|---|---|---|
| `execution_depth` | `direct_answer` | RAG 또는 cached context로 바로 답변. 운영 tool 호출 없음 |
|  | `single_lookup` | read-only tool 1개만 호출 |
|  | `bounded_lookup` | read-only tool 2개까지 호출 |
|  | `incident_diagnosis` | classifier/RCA까지 실행 |
|  | `remediation_planning` | RCA 뒤 remediation, policy_guard, approval_gate, verifier, report까지 실행 |
|  | `action_execution` | 승인/변경관리/실행 경로 |
| `max_tool_calls` | int | 해당 turn에서 허용되는 read-only tool 호출 수 |
| `allow_react_loop` | bool | ReAct tool loop 허용 여부 |
| `history_policy` | `none`/`summary`/`full` | 다음 agent에 전달할 대화 이력 범위 |

현재 서비스 기준 기본값:

| 사용자 의도 | 권장 depth | max_tool_calls | allow_react_loop |
|---|---|---|---|
| 용어 설명, "DLQ가 뭐야?" | `direct_answer` | 0 | false |
| "파이프라인 목록 보여줘" | `single_lookup` | 1 | false |
| "현재 상태 요약해줘" | `bounded_lookup` | 2 | false |
| "왜 장애났는지 분석해줘" | `incident_diagnosis` | 4~6 | true |
| "원인과 조치 후보 알려줘" | `remediation_planning` | 4~6 | true |
| "재시작해줘/승인할게" | `action_execution` | 0 read-only, mutation은 governance 경로 | false |

#### 6.4.2 `simple_query` lookup 경로

현재 `simple_query`는 depth에 따라 tool budget을 달리하지만 stage 경로는 lookup depth에서 공통적으로 `planner -> retrieval -> report`다. `direct_answer`는 `max_tool_calls=0`으로 운영 tool을 호출하지 않는다.

```text
simple_query.direct_answer
  -> planner
  -> retrieval(max_tool_calls=0)
  -> report

simple_query.single_lookup
  -> planner
  -> retrieval(단일 tool)
  -> direct report

simple_query.bounded_lookup
  -> planner
  -> retrieval(max_tool_calls 제한)
  -> report
```

Verifier는 모든 simple_query에 넣지 않는다. 운영 변경, RCA 결론, 사용자 영향 판단처럼 검증 가치가 큰 출력에만 둔다. 단순 조회 답변은 schema validation과 evidence citation check로 대체한다.

#### 6.4.3 Planner prompt의 "최소 충분 조회" 원칙

현재 prompt는 이미 아래 원칙을 적용한다.

- 기본은 **가장 좁은 tool 1개**다.
- 여러 tool은 사용자가 "원인 분석", "상관관계", "상세 진단"을 요청했거나 단일 tool로 답변 불가능할 때만 선택한다.
- 출력은 선택한 `tools` 배열과 짧은 `reason` 두 키만 담은 JSON object 하나다.
- 선택 tool 수 상한은 prompt 원칙이 아니라 LLM 출력 이후 코드(planner)에서 결정론적으로 제한한다.

#### 6.4.4 ReAct 루프를 조건부로만 켠다

`run_tool_loop`는 chaining이 필요한 경우 유용하다. 하지만 모든 `simple_query`에 켜면 과도하다. 현재 simple_query의 식별자 조회는 planner가 만든 capped flat plan과 depends_on 실행으로 처리하고, ReAct loop는 incident/remediation depth에서만 허용한다.

현재 조건:

- `allow_react_loop=true`일 때만 실행한다.
- `execution_depth in {"incident_diagnosis", "remediation_planning"}`에서만 허용한다(`_DEPTH_BUDGET` 기준).
- `simple_query.single_lookup`과 `bounded_lookup`에서는 끈다(`allow_react_loop=false`).
- `max_steps`(react_max_steps)는 mode별로 다르게 둔다.
  - direct/single: 0
  - bounded: 2 (단 `allow_react_loop=false`라 실제 루프는 돌지 않음)
  - incident/remediation: 6

#### 6.4.5 대화 히스토리 input filter

현재 depth budget은 `history_policy`를 함께 반환하고, runner가 일부 경로에서 이 정책을 사용한다. 이 방식은 후속 질문에는 좋지만 Router/Planner에 불필요한 장애 키워드를 주입할 수 있으므로 정책별 품질 검증이 필요하다.

개선:

- Router에는 "최근 사용자 발화 + 짧은 thread summary + 직전 run mode/action 상태"만 전달하는 방향을 유지한다.
- Planner에는 Router가 추출한 `normalized_intent`, `entities`, `execution_depth`만 전달한다.
- Retrieval/ReAct에는 필요한 tool 입력만 전달하고, 전체 대화 히스토리는 기본 차단한다.
- Report만 사용자 친화성을 위해 요약된 history를 볼 수 있게 한다.

### 6.5 현재 agent 구조 검증

#### 판정

현재 Bifrost의 agent 구조는 **큰 방향은 맞고, 자연어 질의 과분해를 줄이는 depth-aware 경로가 구현되어 있다**. 남은 위험은 routing 휴리스틱과 호출량 회귀 검증이다.

맞는 부분:

- Router / Planner / Retrieval / Classifier / RCA / Remediation / Verifier / Report 역할 분리는 서비스 성격에 맞다.
- Policy Guard, Executor, Approval Gate, Change Gate를 LLM 밖의 결정론적 단계로 둔 것은 옳다.
- Tool registry allowlist와 Spring Boot 내부 API 위임 구조는 안전하다.
- ReAct loop는 깊은 인시던트 진단이나 remediation planning에서 "식별자 발견 -> 후속 tool 호출" 같은 운영 조회 chaining을 처리할 때 적합하다.

문제 있는 부분:

- Router의 depth 휴리스틱이 실제 사용자 질의를 충분히 잘 분류하는지 검증이 부족하다.
- `simple_query.direct_answer`도 stage상 planner/retrieval/report를 지나므로 no-stage fast path는 별도 개선 여지가 있다.
- Planner와 ReAct가 둘 다 tool 선택권을 갖는 경로는 budget 회귀 테스트로 묶어야 한다.
- 대화 히스토리 정책이 summary/full 경로에서 이전 장애 맥락 오염을 막는지 검증이 필요하다.
- 설계 문서의 일부 설명이 현재 코드와 맞지 않는다.

서비스에 맞는 목표 구조:

```text
Router / Query Scope Guard
  -> direct_answer
  -> single_lookup
  -> bounded_lookup
  -> incident_diagnosis
  -> remediation_planning
  -> action_execution
```

그리고 각 depth별로 호출 가능한 stage를 제한한다.

| depth | 허용 stage |
|---|---|
| `direct_answer` | planner, retrieval(max_tool_calls=0), report |
| `single_lookup` | planner, retrieval, report |
| `bounded_lookup` | planner, retrieval, report |
| `incident_diagnosis` | correlation, planner, retrieval, classifier, rca, verifier, report |
| `remediation_planning` | correlation, planner, retrieval, classifier, rca, remediation, policy_guard, approval_gate, verifier, report |
| `action_execution` | policy_guard, approval_gate, change_gate, executor, verifier, report |

### 6.6 코드 개선 체크리스트

| 우선순위 | 개선 | 코드 영역 | 완료 기준 |
|---|---|---|---|
| 구현됨·검증 필요 | RouterOutput에 `execution_depth`, `max_tool_calls`, `allow_react_loop`, `history_policy` 추가 | [schemas/outputs.py](../../services/ai-service/app/schemas/outputs.py), [agents/router.py](../../services/ai-service/app/agents/router.py), [prompts/router.py](../../services/ai-service/app/prompts/router.py) | 자연어 질의별 실행 깊이가 테스트로 고정됨 |
| 구현됨·검증 필요 | `stages_for_mode`를 depth-aware로 변경 | [supervisor/transitions.py](../../services/ai-service/app/supervisor/transitions.py), [workflow/runner.py](../../services/ai-service/app/workflow/runner.py) | simple direct/single lookup이 classifier/rca/verifier를 호출하지 않음 |
| 높음 | Planner prompt를 최소 충분 조회 원칙으로 수정 | [prompts/planner.py](../../services/ai-service/app/prompts/planner.py), [agents/planner.py](../../services/ai-service/app/agents/planner.py) | "현황" 질의가 기본 1~2개 tool로 제한됨 |
| 구현됨·검증 필요 | ReAct loop를 `allow_react_loop`와 `max_tool_calls`로 제한 | [agents/retrieval.py](../../services/ai-service/app/agents/retrieval.py), [agents/agentic.py](../../services/ai-service/app/agents/agentic.py) | simple_query에서 의도치 않은 3개 이상 tool 호출이 발생하지 않음 |
| 부분 구현 | agent별 history input filter 도입 | [workflow/runner.py](../../services/ai-service/app/workflow/runner.py) | Router/Planner가 전체 대화 원문 대신 요약 intent만 받음 |
| 중간 | no-tool/direct-answer fast path 추가 | [workflow/runner.py](../../services/ai-service/app/workflow/runner.py), [agents/retrieval.py](../../services/ai-service/app/agents/retrieval.py) | 용어/문서 질의는 운영 API 호출 없이 답변 |
| 중간 | 호출량 회귀 테스트 추가 | `tests/test_router_llm_routing.py`, `tests/test_planner_llm_routing.py`, `tests/test_agentic_loop.py`, `tests/test_routes_agent.py` | 대표 자연어 질의별 agent count/tool count가 assertion됨 |
| 낮음 | 설계 문서와 구현 차이 정리 | [contract-agent-roles.md](./backend-fastapi/contract/contract-agent-roles.md), [agent-principles.md](./backend-fastapi/agent-principles.md) | Retrieval depends_on/ReAct/Verifier 현재 구현 설명이 코드와 일치 |

---

## 7. 개선 로드맵

| 단계 | 개선 항목 | 주요 코드/문서 영역 | Bifrost 산출물 | 완료 기준 |
|---|---|---|---|---|
| 1 | 자연어 질의 실행 깊이 제어 | `RouterOutput`, `transitions.py`, `runner.py`, `planner.py`, `retrieval.py`, `agentic.py` | `execution_depth`, `max_tool_calls`, `allow_react_loop`, `history_policy`, direct/single/bounded lookup flow | 기본 구현은 완료. 단순 질의가 필요한 agent/tool만 호출하는지 대표 질의별 호출량 테스트로 고정 필요 |
| 2 | agent 실행 관측성 | `run_telemetry`, `state_patch`, `runner.py`, tool registry, tracing 설정 | schema/collector와 stage latency·agent 집계는 구현됨. tool/LLM/handoff instrumentation, `cost_by_stage`, `budget_used`는 보강 필요 | 자연어 질의/인시던트 분석별 호출량·latency·비용을 run_id로 설명 가능 |
| 3 | threshold governance | RCA threshold config, Spring workspace settings, threshold registry migration | threshold registry/API는 구현됨. RCA scoring과 일부 Spring 설정을 registry source of truth로 연결해야 함 | 0.60/0.80/0.5%/2.0% 같은 값의 변경 근거와 이력이 조회되고 runtime이 그 값을 사용함 |
| 4 | run 단위 재현성 스키마 확장 | `services/ai-service/app/workflow/**`, run/state schema, `services/ai-service/app/llm/model_router.py` | RCA run record에 `model_id`, `model_tier_map`, `prompt_version/hash`, `catalog_version`, `evidence_matrix_version`, `runbook_version`, `corpus_manifest_hash`, `eval_dataset_version`, `code_commit_sha` 저장 구현됨 | 실제 LLM 호출 모델 snapshot 강제 여부와 과거 run 재현 리허설 검증 |
| 5 | 자동 롤백 실행 경로 | `workflow/stages/executor.py`, `workflow/stages/rollback.py`, remediation runbook catalog, operations-backend action API | executor 실패/BLOCKED inverse rollback은 구현됨. Verifier 실패 후 runbook `rollback_plan` 실행은 보강 필요 | medium 이하 승인 조치에서 성공 조건 미달 시 자동 원복됨. high-risk rollback은 승인 대기로 남음 |
| 6 | RCA gold set·라벨링 프로토콜 | incident persistence, RCA output schema, 운영 검수 UI/API, `docs/design/backend-fastapi/contract/**` | gold set schema/API, 라벨링 guide, 운영자 feedback 승격은 구현됨 | 최소 30~50건의 운영 평가셋 확보. 운영 UI와 inter-review consistency check 보강 |
| 7 | RCA 성능 리포트 | ai-service eval script/report, RCA result storage, catalog taxonomy | AC@1/AC@3/AC@5/Avg@5, 계층별 성능, UNKNOWN 비율 | confidence threshold와 UNKNOWN 기준을 데이터로 조정 가능 |
| 8 | ECE 캘리브레이션 | confidence scoring module, evaluation report, RCA threshold config | confidence bin별 accuracy/gap/ECE 리포트 | 과신 구간 확인 후 confidence cap 또는 UNKNOWN threshold 재설정 |
| 9 | online feedback·drift 감시 | report snapshot, incident review API/UI, run metrics dashboard | online feedback event와 drift report는 구현됨. dashboard, 운영 기준, 자동 재보정 연결은 보강 필요 | 월별 리포트 외에도 threshold 재보정 trigger가 운영 지표로 발생 |
| 10 | 사용자 영향 SLI 정의 | operations-backend monitoring/incident service, metrics schema, Prometheus queries, `docs/design/backend-springboot/monitoring.md` | freshness, latency, success, completeness, provisioning SLI 정의·측정 API는 구현됨 | baseline 데이터로 `good_event/total_event` objective와 low-traffic 보정 확정 |
| 11 | SLO burn-rate 알림 도입 | `IncidentService.java`, alert rule config, severity mapping, notification routing | page/ticket/diagnostic routing과 severity_reason 저장은 구현됨 | page는 SLO burn-rate 위반 중심, lag/FAILED 등 원인 지표는 diagnostic/fallback으로 분리되는지 검증 |
| 12 | 인과/상관 증거 태그 | `catalogs/evidence_matrix.py`, `catalogs/root_causes.py`, `agents/rca.py`, RCA prompt/output schema | `EvidenceRule.causality_type`, `temporality_required`, `causal_chain_step` | 필드와 temporal required 강등 로직은 구현됨. 전체 profile 태그 일관성·평가 fixture 보강 필요 |
| 13 | KEDB형 카탈로그 확장 | root cause catalog, remediation runbooks, policy matrix, owner metadata | KEDB schema/API/repository와 report surface는 구현됨 | RCA 결과가 조치·rollback·운영 소유자·재발 방지까지 자동 누적되는 경로 검증 |

---

## 8. 후속 과제

이전 보류 항목 2건은 §Q4·§4.2에서 1차 출처로 **해소 완료**했다. 남은 항목은 다음과 같다.

| 항목 | 상태 | 대응 |
|---|---|---|
| Bifrost SLO 수치 확정 | 외부 표준은 방법론과 시작값을 제공하지만, 최종 수치는 서비스 특성에 맞춰야 함 | 2~4주 baseline 데이터로 freshness/latency/success/completeness 목표치 확정 |
| RCA gold set 확보 | gold set schema/API와 운영자 feedback 승격은 구현됨. 실제 resolved incident 정답셋 규모가 부족함 | 운영자가 확정한 `accepted_root_cause_id`, trigger, evidence를 축적 |
| RCA 라벨링 기준 확정 | 라벨링 guide/API는 구현됨. trigger, symptom, root cause, contributing factor 혼동이 AC@k/ECE를 왜곡하지 않는지 운영 검수 일관성 확인 필요 | 운영자 검수 UI와 inter-review consistency check 보강 |
| UNKNOWN 임계값 확정 | 현재 0.60은 bootstrap 기준 | ECE와 AC@k 결과로 threshold 재조정 |
| threshold registry 설계 | registry/API와 metadata 스키마는 구현됨. runtime threshold가 전부 registry를 source of truth로 쓰지는 않음 | RCA/Spring threshold 연결과 calibration 기반 변경 절차 확정 |
| agent 실행 관측성 | telemetry schema/collector는 구현됨. tool/LLM/handoff call site와 비용·budget 집계가 부족함 | `called_agents`, `called_tools`, `tool_call_count`, `latency_by_stage`, `cost_by_stage`, `budget_used` 저장 완성 |
| online drift 기준 | online feedback event와 drift report는 구현됨. 운영 기준과 재보정 trigger 연결이 미완성 | UNKNOWN 비율, override 비율, confidence 분포, root cause 분포 drift 기준 운영화 |
| 정적 임계값 vs 이상탐지 | ML 이상탐지 보강 여부는 미결정 | 별도 PoC 필요. 현 단계 우선순위 낮음 |

---

## 9. 인용 시 주의

아래 주장은 적대적 검증에서 반증되었으므로 **사용 금지**한다.

- "graphical event model이 다른 인과기법보다 precision/recall 우월" — 반증(0-3)
- "Google 5대 근본원인 카테고리 = 산업 표준 택소노미" — 반증(0-3). Google은 표준 분류로 처방하지 않음
- "블랙박스 LLM은 기존 캘리브레이션 적용 불가" — 반증(0-3)
- "에이전트 reasoning 환각이 인과/상관 혼동을 RCA 환각으로 직접 식별" — 반증(0-3)
- **NIST RMF/600-1은 자발적(voluntary) 문서**다. "요구한다"가 아니라 "권고한다"로 표기한다.

---

## 10. 주요 출처

### 표준/거버넌스

- [NIST AI 600-1](https://nvlpubs.nist.gov/nistpubs/ai/NIST.AI.600-1.pdf)
- [NIST AI 100-1](https://nvlpubs.nist.gov/nistpubs/ai/nist.ai.100-1.pdf)
- [ISO/IEC 42001](https://www.iso.org/standard/42001)

### MLOps/버전관리

- [MLflow Model Registry](https://mlflow.org/docs/latest/ml/model-registry/)
- [Langfuse Prompt Management](https://langfuse.com/docs/prompt-management/get-started)

### SRE/운영

- [SRE Book — Postmortem Culture](https://sre.google/sre-book/postmortem-culture/)
- [SRE Workbook — Postmortem Analysis](https://sre.google/workbook/postmortem-analysis/)
- [SRE — Automation](https://sre.google/sre-book/automation-at-google/)
- [SRE Book — Monitoring Distributed Systems](https://sre.google/sre-book/monitoring-distributed-systems/)
- [SRE Workbook — Alerting on SLOs](https://sre.google/workbook/alerting-on-slos/)
- [AWS OPS06-BP04](https://docs.aws.amazon.com/wellarchitected/latest/operational-excellence-pillar/ops_mit_deploy_risks_auto_testing_and_rollback.html)
- [Argo Rollouts — Analysis](https://argo-rollouts.readthedocs.io/en/stable/features/analysis/)
- [Datadog — Burn Rate Alerts](https://docs.datadoghq.com/service_level_objectives/burn_rate/)
- [Atlassian — Problem Management](https://www.atlassian.com/itsm/problem-management/process)
- [PagerDuty — Severity Levels](https://response.pagerduty.com/before/severity_levels/)

### 인과추론

- [Bradford Hill criteria](https://en.wikipedia.org/wiki/Bradford_Hill_criteria)
- [Bareinboim et al. — On Pearl's Hierarchy](https://philpapers.org/rec/BAROPH-2)

### 환각/RAG/abstain

- [Self-RAG](https://arxiv.org/abs/2310.11511)
- [Abstention 서베이](https://direct.mit.edu/tacl/article/doi/10.1162/tacl_a_00754/131566/Know-Your-Limits-A-Survey-of-Abstention-in-Large)
- [Know Your Limits — arXiv](https://arxiv.org/abs/2407.18418)
- [Self-Consistency](https://arxiv.org/abs/2203.11171)
- [에이전트 환각 서베이](https://arxiv.org/html/2509.18970v1)

### 자동 RCA/평가

- [ASE'24 How Far Are We](https://arxiv.org/abs/2408.13729)
- [CausalRCA](https://arxiv.org/pdf/2209.02500)
- [IBM CODS 2021](https://dl.acm.org/doi/fullHtml/10.1145/3430984.3431027)
- [RCAEval](https://arxiv.org/html/2412.17015v1)
- [PACE-LM](https://arxiv.org/pdf/2309.05833)
- [On Calibration of Modern Neural Networks](https://arxiv.org/abs/1706.04599)
- [Saha & Hoi](https://arxiv.org/abs/2204.11598)

### Agent orchestration/tool-use

- [OpenAI Agents SDK — Handoffs](https://openai.github.io/openai-agents-python/handoffs/)
- [OpenAI Agents SDK — Guardrails](https://openai.github.io/openai-agents-python/guardrails/)
- [Anthropic — Tool Use](https://platform.claude.com/docs/en/agents-and-tools/tool-use/overview)
- [Microsoft AutoGen — Termination](https://microsoft.github.io/autogen/stable/user-guide/agentchat-user-guide/tutorial/termination.html)
- [ReAct](https://arxiv.org/abs/2210.03629)

### 한계

인과추론·LLM RCA는 빠르게 변하는 분야(핵심 출처 2021~2025)이며, 학술 벤치마크는 일반 마이크로서비스 대상이라 우리 data-pipeline 도메인에는 보강·교차검증 용도로만 적용해야 한다. 벤더(Datadog/Dynatrace/Moogsoft/BigPanda/PagerDuty)는 RCA 정확도·abstain 임계값을 정량 공개하지 않는다. ISO·TACL 일부 페이지는 403으로 arXiv 미러·다중출처로 교차확인했다.
