# Bifrost fix final EKS test report - 2026-06-14

이 문서는 2026-06-14 최종 재배포 후 실제 EKS 클러스터에 배포된 서비스만 대상으로 검증한 결과다. 이전 중간 실패/부분 성공 이력은 최종 판정에서 제외했다.

## 대상

| 항목 | 값 |
|---|---|
| 테스트 일시 | 2026-06-14 KST |
| 외부 엔드포인트 | `https://bifrost.skala-ai.com` |
| EKS context / namespace | `skala_student` / `bifrost-system`, `platform-kafka` |
| 최종 Jenkins 빌드 | `bifrost-ci #44` SUCCESS |
| 최종 GitOps 커밋 | `30bd618` (`ci: deploy ai-service @ 59939757`) |
| 배포 이미지 | ai-service `59939757`, operations-backend `ce1677e0`, frontend `29db66cb` |
| 로컬 회귀 테스트 | ai-service `419 passed, 2 warnings` |
| 계정 | `ta@bifrost.io` |
| Demo Team workspace | `3ebaa2d6-39d7-4d48-ac9c-bf56ef1c3058` |
| 장애 테스트 workspace | `30bd2cc2-54f2-4cf1-a6db-3c2715e424e2` |
| 장애 테스트 incident | `0e074f6d-769a-47b4-92b6-0fafb7edfb24` |

## 최종 결과

| 이슈 | 최종 결과 | 검증 증거 |
|---|---|---|
| #667 | PASS | `get_traces` request `req_b84d2a65527a`, `status=success`; `get_connector_task_trace` request `req_ab56c8c48704`, `status=success`; `URI is not absolute` 재발 없음 |
| #676 | PASS | run `run_81c080c2753747cc`; Demo Team 답변에서 `*-source`는 소스 커넥터, `*-sink`는 싱크 커넥터로 구분 |
| #678 | PASS | incident_analysis run `run_b7e02664e3e04891`; report root cause `CONNECTOR_TASK_FAILED`, `verified=true`; UNKNOWN 0% 단독 수렴 없음 |
| #668 | PASS | 같은 run에서 action 후보 10건 생성; `restart_connector` `runtime_tool` 후보 1건 노출 |
| #677 | PASS | `/api/v1/agent/runs/run_b7e02664e3e04891/actions` 결과에서 fake bucket action 0건; `candidates`, `policy_decisions`, `approval_requests`가 action_id로 노출되지 않음 |
| #670 | PASS | `verification_completed: pass` 이후 `report_preview_available` 1건 발행; timestamp 순서 `12:08:11.310 pass` -> `12:08:11.315 preview` |
| #671 | PASS | incident report 1건 저장; report body에 policy decisions/action 후보 포함; UI Run 버튼 전제인 `restart_connector` 후보와 connector target 충족 |
| #669 | PASS | action_execution run `run_31c323b60f8243e6`; approval `8da79d2b-3e64-45c5-bed7-914cd66fac64` 생성 -> 승인 -> `execution_started` -> mutation 호출 -> verifier pass |
| #681 | PASS | pipeline delete HTTP 204, workspace delete HTTP 204; 35초 후 namespace/KafkaUser/Secret 모두 NotFound |

## 핵심 실행 로그

### 배포

| 단계 | 결과 |
|---|---|
| Git commit | `5993975 fix(ai): verify evidence-backed RCA candidates for #671` |
| Jenkins | `bifrost-ci #44` SUCCESS |
| Harbor image | `harbor.harbor.svc.cluster.local/library/bifrost-ai-service:59939757` |
| ArgoCD | `2-bifrost-services` Synced / Healthy / Succeeded |
| Kubernetes rollout | `deployment/ai-service` successfully rolled out |

### 서비스 헬스

| API | 결과 |
|---|---|
| `/api/v1/health` | `status=ok` |
| `/api/v1/ready` | `status=ready`, dependencies `spring_operations=ok`, `llm_provider=ok`, `agent_run_store=ok`, `vector_store=ok`, `evidence_store=ok` |

### Incident Analysis

| 항목 | 값 |
|---|---|
| run_id | `run_b7e02664e3e04891` |
| status | `completed` |
| verification | `검증: pass`, `report 검증: pass` |
| report preview | 1건, verifier pass 이후 발행 |
| report snapshots | 1건 |
| root cause | `CONNECTOR_TASK_FAILED` |
| action count | 10건 |
| restart action | `runtime_tool`, `tool_name=restart_connector`, 1건 |

### HITL Approval Execution

| 항목 | 값 |
|---|---|
| run_id | `run_31c323b60f8243e6` |
| approval_id | `8da79d2b-3e64-45c5-bed7-914cd66fac64` |
| approval decision | `approved` |
| final status | `completed` |
| key events | `approval_required` -> `execution_started` -> `execution_completed` -> `verification_completed: pass` -> `report 검증: pass` |
| mutation result | backend mutation reached; `completed=0`, `failed=1`, `blocked=0` |

`execution_completed`의 `failed=1`은 이미 장애 상태인 실제 connector에 restart mutation을 호출한 운영 결과이며, #669의 승인 레코드 생성/승인/hash 검증/실행 도달 조건은 모두 통과했다.

### Cleanup

| 대상 | 결과 |
|---|---|
| pipeline `126abce5-73b0-4a1d-bf97-1548e1a5ce2d` | DELETE HTTP 204 |
| workspace `30bd2cc2-54f2-4cf1-a6db-3c2715e424e2` | DELETE HTTP 204 |
| namespace `e2e-final-671-20260614205340` | NotFound |
| KafkaUser `proj-e2e-final-671-20260614205340-user` | NotFound |
| Secret `proj-e2e-final-671-20260614205340-user` | NotFound |

## 결론

최종 배포본 기준 #667, #668, #669, #670, #671, #676, #677, #678, #681 전체 테스트가 PASS다. 테스트용 EKS 리소스도 삭제 후 잔존 없음으로 확인했다.
