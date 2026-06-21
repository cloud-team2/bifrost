# 04. 예상 질의응답 — Agent: 장애 대응

> 백업 슬라이드 / 발표자 대비. 리뷰어 도전 질문에 대한 30초 답변 + 근거.
> 근거: [rca-standards-review.md §3](../design/rca-standards-review.md).

---

## Q1. 환각 통제: "AI에 단순히 로그만 취합해 전달하면 RCA가 진행되나? RCA 프로세스가 누락된 채 LLM에 의존적이면 환각이 나는데 어떻게 대응하나?"

**30초 답변**
> 로그를 LLM에 던지지 않습니다. RCA는 순수 LLM 호출이 아니라 **고정 카탈로그(8계층 35개) + required/supporting/negative 증거 매트릭스 + 신뢰도 스코어링 + 증거 부족 시 기권(UNKNOWN)** 으로 LLM을 제약합니다. LLM은 상위 2후보 신뢰도 차가 0.10 미만일 때만 타이브레이커로, 그것도 **원문 로그가 아닌 증거 요약만** 받습니다. 이는 NIST AI 600-1(grounding), Self-RAG(증거 선별), Abstention 서베이(증거 부족 시 보류) 권고와 정합합니다.

**근거 한 표**

| 표준 | 권고 | 우리 매핑 |
|---|---|---|
| NIST AI 600-1 | grounding·인용 검증 | 증거 매트릭스 + `evidence_id` 인용 |
| Self-RAG (ICLR'24) | 증거 선별·자기검증 | required/supporting/negative |
| Abstention (TACL'25) | 증거 부족 시 보류 | `UNKNOWN_WITH_EVIDENCE_GAP` |
| PACE-LM (FSE'24) | LLM 단독 RCA는 GPT-4 43.4%, 신뢰도로 채택/보류 | confidence cap + 기권 |

> 보강: "RCA 프로세스가 누락"이 아니라, 프로세스가 **카탈로그+증거 매트릭스+신뢰도 게이트로 코드에 고정**되어 있습니다(매 run 동일 절차).

---

## Q2. Agent 단에 rollback plan이 있나? (실패 시나리오)

**30초 답변 (정직하게 — 부분 충족)**
> 승인 게이트·멱등성 키·완전 감사로그·UNKNOWN 정지는 NIST·Google SRE 권고를 충족합니다. `rollback_plan` 필드는 존재하고 Change Gate가 필수 메타데이터로 검증합니다. 다만 AWS Well-Architected(OPS06-BP04)가 권고하는 **"검증 실패 시 자동 롤백 실행"은 아직 미구현**이며, automation maturity 로드맵상 다음 단계입니다. 현재 실패 시 동작은 Verifier 1회 루프백 + 사람 개입입니다.

**로드맵 산출물**: `pre_change_snapshot`, `rollback_action_id`, `rollback_status`, rollback 감사 이벤트 → low/read-only부터 자동 롤백, high는 롤백도 승인 대상.

---

## Q3. Version 관리가 되나?

**30초 답변**
> 카탈로그(`catalog_version`), 상태 이력(append-only `StatePatch`), 모델 tier 매핑은 버전 추적되며 run 레코드·health API에 노출됩니다. ISO/IEC 42001·NIST AI RMF 기준 **재현성 보강 포인트**는 모델이 `gpt-4o` 같은 별칭이라 날짜 스냅샷 핀고정이 아니라는 점입니다. run record에 `model_id`·`prompt_hash`·`evidence_matrix_version`·`code_commit_sha` 추가가 다음 단계입니다.

---

## Q4. 에이전트 설계에서 장애 알림의 기준은?

**30초 답변**
> 현재는 **원인 기반 정적 임계값 + ERROR/WARN 게이팅 + 에스컬레이션**입니다. 예: consumer lag ≥ 5,000(WARN)/≥ 50,000(CRIT), connector FAILED 즉시 CRITICAL, 동일 리소스 30분 내 WARN 2건→WARNING→ERROR 동반 시 CRITICAL. Google SRE 표준에 맞춰 다음 단계는 **사용자 영향 SLI(데이터 신선도·end-to-end 지연) 기반 burn-rate page**를 상위에 두고, 원인 신호는 ticket/diagnostic으로 낮춰 알림 피로를 줄이는 것입니다.

**알림 라우팅 로드맵**

| 신호 | 현재 | 개선 후 |
|---|---|---|
| 사용자 영향 SLO burn-rate 급속 | (없음) | CRITICAL + **page** |
| consumer lag WARN/CRIT | 임계값 incident | 영향 있으면 page/ticket, 없으면 diagnostic |
| connector FAILED | 즉시 CRITICAL | SLI 악화 시 page/ticket, 아니면 ticket+RCA 증거 |

> severity도 ERROR/WARN 단일축 → **Impact × Urgency**(ITIL/PagerDuty)로 보강 예정. (`severity_reason`에 근거 기록)

---

## 추가 예상 질문 (대비)

- **"왜 임계값이 5,000/50,000인가?"** → spec.md 부록 B 초기값. "수치는 절대 표준이 아니라 자체 데이터로 캘리브레이션"이 입장(§5.1). threshold registry(이름·버전·근거·owner)가 로드맵.
- **"자연어 질의가 에이전트를 과다 호출하지 않나?"** → 맞는 지적. 현재 고정 stage chain → depth-aware Router(execution_depth/max_tool_calls)로 최소 실행 경로 제어가 로드맵(§6).
- **"정보 유출/프롬프트 인젝션 방어?"** → 원문 로그·시크릿을 State에 미저장(redaction), LLM엔 증거 요약만, 유도 공격 32건 차단(p.16).
- **"멀티테넌트 격리?"** → 워크스페이스=테넌트 네임스페이스 격리, 로그/지표 쿼리도 프로젝트 스코프 강제.

## 근거

- §2.2(안전장치), §2.3(버전), §2.4(알림), §3 Q1~Q4 전체, §5.4(라우팅), §6(질의 깊이)
