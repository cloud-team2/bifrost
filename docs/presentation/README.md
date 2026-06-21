# Bifrost 발표 보강 자료 (Agent: 장애 대응)

> Canva 덱(Skala-Final-Project)에 들어갈/수정할 내용을 페이지별로 정리한 작업 노트.
> 모든 주장은 코드·표준 문서 근거를 달았다. 근거 원문: [docs/design/rca-standards-review.md](../design/rca-standards-review.md).
> 작성 기준일: 2026-06-22. 시스템 사실은 develop 코드 기준.

## 이 폴더 구성

| 파일 | 대응 발표 주제 | 대응 Canva 페이지 |
|---|---|---|
| [01-ai-architecture.md](01-ai-architecture.md) | **AI 아키텍처** | 신규 + 기존 p.9/10 보강 |
| [02-rca-hallucination-control.md](02-rca-hallucination-control.md) | **AI 환각 통제 방안: RCA 국제 표준 룰** (+ 리스크 관리 지표) | 신규 |
| [03-ai-metrics.md](03-ai-metrics.md) | **AI 성능지표 / 성능 지표** | 기존 p.16 보강 |
| [04-qa-incident-response.md](04-qa-incident-response.md) | **예상 질의응답** (환각 통제·롤백·버전관리·알림기준) | 백업 슬라이드 |

## 발표 핵심 메시지 (한 줄)

> **"로그를 LLM에 던지는 RCA"가 아니다.** 8계층 35개 근본원인 카탈로그 + required/supporting/negative 증거 매트릭스 + 신뢰도 스코어링 + 증거 부족 시 기권(UNKNOWN)으로 LLM을 제약하고, 모든 변경은 사람 승인 뒤 실행한다 — NIST·Google SRE·Self-RAG·Abstention 권고와 정합.

## 정직성 프레이밍 (중요)

발표 주제 자체가 "환각 통제"이므로, **구현 완료 vs 로드맵을 섞지 않는다.** 각 슬라이드에서:
- ✅ **구현 완료**: 증거기반 RCA, UNKNOWN 기권, 승인 게이트, 멱등성, 감사로그, 카탈로그 버전추적, 트리거/근본원인 분리(#962), 운영자 피드백 루프(#964), lag 추세 증거(#957)
- 🔜 **로드맵(표준 대비 갭)**: 조치 실패 시 자동 롤백, 모델 ID 날짜 스냅샷 핀고정, SLO burn-rate 알림 전환, ECE 캘리브레이션 정기화

> 갭을 숨기지 말 것. "표준이 권고하는 지표·절차를 채택했고, 구체 수치는 자체 데이터로 캘리브레이션한다"가 우리 입장(§5.1).

## 이미지/캡처 체크리스트

| 위치 | 필요 자료 | 확보 방법 |
|---|---|---|
| 01 아키텍처 | 8-에이전트 파이프라인 다이어그램 | 본 md의 Mermaid 사용(렌더) 또는 Canva 도형 |
| 01 아키텍처 | 결정론적 안정장치(정책·승인·예산 가드) 위치 | Mermaid 서브그래프 |
| 02 RCA 룰 | 증거 매트릭스(required/supporting/negative) 예시 | 본 md 표 → Canva 표 |
| 02 RCA 룰 | 신뢰도 게이트 흐름(0.60 미만→UNKNOWN) | Mermaid flow |
| 03 성능지표 | 인시던트 상세 RCA 화면 캡처 | **라이브 UI 캡처 필요**: bifrost.skala-ai.com → 인시던트 상세 → 근본원인·RCA 섹션 |
| 03 성능지표 | 운영자 피드백 바(#964) 캡처 | **라이브 UI 캡처 필요**: 인시던트 상세 RCA 섹션 하단 "이 분석이 맞나요?" |
| 04 Q&A | RCA가 SINK_DB_CONNECTION_TIMEOUT로 교정된 리포트 | API 결과 표(02 md에 수록) |

> 라이브 캡처는 발표자가 직접 로그인 상태에서 찍는 것을 권장(인증 필요). 본 노트의 Mermaid·표는 캡처 없이도 슬라이드로 바로 옮길 수 있게 작성했다.

## 이외에 다뤄야 할 부분 (백업/심화)

1. **자동 롤백 로드맵** — 현재 `rollback_plan` 필드+Change Gate 검증은 있으나 자동 실행은 미구현. AWS OPS06-BP04·Argo Rollouts 패턴으로 다음 단계(04 Q&A Q2).
2. **재현성/버전 관리** — `catalog_version`·상태이력은 있음. run record에 model_id 스냅샷·prompt hash 추가가 다음 단계(04 Q&A Q3).
3. **SLO 기반 알림 전환** — 현재 정적 임계값 → 사용자 영향 SLI + burn-rate page(02 리스크 지표, 04 Q&A Q4).
4. **운영자 피드백 → gold set → 캘리브레이션** — #964로 수집 루프 구축 완료. AC@k/ECE 평가가 다음 단계(03 성능지표).
5. **자연어 질의 과다 호출 제어** — depth-aware Router(§6). 데모 안정성·비용 관점에서 질문 가능성 있음.
6. **보안/격리** — 원문 로그·시크릿 미저장(redaction), 멱등성 키, append-only 감사로그, 테넌트 네임스페이스 격리.
