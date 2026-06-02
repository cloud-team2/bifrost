# Spring Boot Operations Backend — API 명세

> [DETAILS.md](../design/backend-springboot/overview.md)에서 분리한 API 레퍼런스다. 요약은 [README.md](../design/backend-springboot/overview.md), 상태값·임계값은 [기능명세서 부록 B](../spec.md#부록-b--리소스-상태값-정의-및-자동-기준-단일-출처).
>
> **섹션 참조 규칙**: 본문의 "이 문서의 §N"은 *이 api.md*의 섹션을, 그 외 `§2(Provisioning)`·`§3(Database Registry)`·`§4(Data Model)` 등은 [design/backend-springboot/](../design/backend-springboot/overview.md) 폴더의 해당 파일(provisioning.md·database-registry.md·data-model.md)을 가리킨다.

---


### 1. 목적

Spring Boot Operations Backend는 **두 개의 API 표면**을 제공한다.

| 표면 | prefix | 소비자 | 내용 |
| --- | --- | --- | --- |
| 플랫폼 API | `/api/v1/...` | Frontend | 워크스페이스·DB·파이프라인 CRUD, 모니터링·이벤트·인시던트 조회 |
| 내부 운영 API | `/internal/ops/...` | FastAPI Agent | Kafka/K8s/Observability 조회, 승인 검증, 상태 변경 실행 |

이 문서는 두 파트로 구성된다. **Part A — 플랫폼 API(`/api/v1`, frontend-facing)** 는 Frontend가 직접 호출하는 전체 엔드포인트, **Part B — 내부 운영 API(`/internal/ops`, agent-facing, §2 이후)** 는 FastAPI Agent가 호출하는 운영 API다. 각 도메인의 동작·구현 상세는 [DETAILS.md §2 Provisioning](../design/backend-springboot/provisioning.md#2-provisioning)·[§3 Database Registry](../design/backend-springboot/database-registry.md#3-database-registry)·[Frontend DETAILS](../design/frontend.md)를 따른다.

FastAPI public API는 [FastAPI api.md](./fastapi.md), Agent tool 매핑은 [FastAPI DETAILS](../design/backend-fastapi/tool-catalog.md#4-tool-catalog)를 기준으로 한다.

---

## Part A — 플랫폼 API (`/api/v1`, frontend-facing)

Frontend가 직접 호출하는 표면. 모든 경로는 `/api/v1` 접두어, 인증은 **사용자 JWT**(Spring 발급), 워크스페이스 범위 리소스는 `/api/v1/workspaces/{wsId}/...`. 화면별 연동은 [Frontend DETAILS](../design/frontend.md), 상태값·임계값은 [기능명세서 부록 B](../spec.md#부록-b--리소스-상태값-정의-및-자동-기준-단일-출처).

### A.0 공통 규칙 (플랫폼)

| 항목 | 규칙 |
| --- | --- |
| 인증 | `Authorization: Bearer <user-JWT>` (FastAPI와 같은 JWT 검증) |
| 응답 봉투 | 성공 `{ ok:true, request_id, data }` / 실패 `{ ok:false, request_id, error:{ code, message } }` (※ 내부 운영 API의 `operation`/`evidence` 봉투와 다름) |
| scope | `{wsId}` = `workspace_id` = `project_id`. 모든 호출은 멤버십·resource ownership 검증 |
| 상태 변경 | 파이프라인 생성/삭제, DB 등록 등은 `audit_event` 기록 |
| 실시간 | 플랫폼 SSE(workspace 범위·장수명)는 Agent SSE와 별도 채널 |

### A.1 인증 (FR-001)

| Method | Path | 설명 |
| --- | --- | --- |
| `POST` | `/api/v1/auth/login` | 이메일·비밀번호 로그인 → JWT 발급 |
| `POST` | `/api/v1/auth/refresh` | 토큰 갱신 |

### A.2 워크스페이스 (FR-002)

| Method | Path | 설명 |
| --- | --- | --- |
| `GET` | `/api/v1/workspaces` | 워크스페이스 목록 |
| `POST` | `/api/v1/workspaces` | 생성 — `project_key` 슬러그 발급 + KafkaUser/ACL 프로비저닝 트리거 + 생성자 `project_member` 자동 등록 |
| `GET` | `/api/v1/workspaces/{wsId}` | 상세 |

### A.3 Database (FR-013 ~ FR-018)

| Method | Path | 설명 |
| --- | --- | --- |
| `GET` | `/api/v1/workspaces/{wsId}/databases?role=&engine=&q=` | 목록(필터) — FR-013 |
| `POST` | `/api/v1/workspaces/{wsId}/databases/connection-test` | 연결 테스트(동적 HikariCP `SELECT 1`, 5s, 예외 분류) — FR-014 |
| `POST` | `/api/v1/workspaces/{wsId}/databases` | 등록 — 자격증명은 `secret_ref`로 보관, 응답 `****` — FR-014 |
| `GET` | `/api/v1/workspaces/{wsId}/databases/{dbId}` | 상세(password 마스킹) |
| `GET` | `/api/v1/workspaces/{wsId}/databases/{dbId}/cdc-readiness` | CDC 준비도 `{overallStatus, checks[...]}` — FR-015 |
| `GET` | `/api/v1/workspaces/{wsId}/databases/{dbId}/schema` | 스키마(테이블·컬럼·인덱스) — FR-016 |
| `GET` | `/api/v1/workspaces/{wsId}/databases/{dbId}/metrics` | TPS·쿼리 응답·활성 연결 — FR-017 |
| `GET` | `/api/v1/workspaces/{wsId}/databases/{dbId}/pipelines` | 이 DB를 쓰는 파이프라인 — FR-018 |

> `role`은 **파생 필터**다(해당 DB가 파이프라인에서 source/sink로 쓰이는지). `database` 엔티티에 역할 컬럼은 없다([DETAILS §4 Data Model](../design/backend-springboot/data-model.md#4-data-model)). 파이프라인 생성 마법사의 소스 후보는 role로 거르지 않고 CDC-ready DB 전체를 대상으로 한다.

### A.4 Pipeline 생성·생명주기 (FR-003 ~ FR-005)

| Method | Path | 설명 |
| --- | --- | --- |
| `GET` | `/api/v1/workspaces/{wsId}/pipelines?status=` | 목록(상태 필터) — FR-003 |
| `POST` | `/api/v1/workspaces/{wsId}/pipelines` | 생성 마법사 제출 `{name, pattern, sourceDbId, sinkDbId?, schema, table}` → `{pipeline_id, status:"creating"}` — FR-004 |
| `GET` | `/api/v1/workspaces/{wsId}/pipelines/{id}` | 상세 |
| `POST` | `/api/v1/workspaces/{wsId}/pipelines/{id}/pause` | 일시정지 → `paused` — FR-005 |
| `POST` | `/api/v1/workspaces/{wsId}/pipelines/{id}/resume` | 재개 → `active` |
| `DELETE` | `/api/v1/workspaces/{wsId}/pipelines/{id}` | 삭제(확인 다이얼로그) |

### A.5 Pipeline 상세 탭 (FR-006 ~ FR-012)

| Method | Path | 설명 |
| --- | --- | --- |
| `GET` | `.../pipelines/{id}/metrics` | produce/consume rate·consumer lag·error rate — FR-006 |
| `GET` | `.../pipelines/{id}/consumer-groups` | 그룹·파티션 lag — FR-007 |
| `GET` | `.../pipelines/{id}/connectors` | connector 상태·task·records/s·마지막 오류 — FR-008 |
| `GET` | `.../pipelines/{id}/sync` | source/sink 행 수·동기화율·지연(CDC) — FR-009 |
| `GET` | `.../pipelines/{id}/messages` | Debezium before/after — FR-010 |
| `GET` | `.../pipelines/{id}/connection-guide` | topic alias·bootstrap·group·코드 스니펫 — FR-011 |
| `GET` | `.../pipelines/{id}/table-mapping` | 컬럼·타입 매핑 — FR-012 |

### A.6 모니터링·이벤트 (FR-019, FR-020, FR-023, FR-024)

| Method | Path | 설명 |
| --- | --- | --- |
| `GET` | `/api/v1/workspaces/{wsId}/events?level=&pipelineId=` | 이벤트 로그 — FR-019 |
| `GET` | `/api/v1/workspaces/{wsId}/overview` | 운영 현황 대시보드 — FR-020 |
| `GET` | `/api/v1/workspaces/{wsId}/cluster` | Broker·Connect worker 현황 — FR-023 |
| `GET` | `/api/v1/workspaces/{wsId}/resource-events` | 리소스 이벤트 로그 — FR-024 |

### A.7 인시던트 (FR-021)

| Method | Path | 설명 |
| --- | --- | --- |
| `GET` | `/api/v1/workspaces/{wsId}/incidents` | 인시던트 목록(open/investigating/resolved) |
| `GET` | `/api/v1/workspaces/{wsId}/incidents/{id}` | 근본 원인·영향 범위·관련 이벤트 타임라인 |

> incident·alert·모니터링 데이터의 source of truth는 Spring Boot다. AI 분석 run은 FastAPI([api.md](./fastapi.md)) 소관.

### A.8 실시간 (SSE)

| Method | Path | 설명 |
| --- | --- | --- |
| `GET` | `/api/v1/workspaces/{wsId}/events/stream` | 플랫폼 SSE — `pipeline_status_changed`·`connector_state_changed`·`incident_opened`/`incident_updated` |

> `EventSource`는 헤더를 못 붙이므로 JWT는 `?access_token=` 쿼리로 전달한다([Frontend DETAILS](../design/frontend.md) §11).

---

## Part B — 내부 운영 API (`/internal/ops`, agent-facing)

아래 §2~§26은 FastAPI Agent가 호출하는 내부 운영 API다.

### 2. 공통 규칙

| 항목 | 규칙 |
| --- | --- |
| Canonical prefix | `/internal/ops/projects/{project_id}` |
| JSON field | snake_case |
| timestamp | ISO-8601 UTC |
| 인증 | FastAPI service identity |
| mutation idempotency | `X-Idempotency-Key` header 필수 |
| evidence | raw content 대신 evidence reference 반환 |

모든 endpoint는 project scope, resource ownership, caller identity를 검증한다.

### 3. 공통 Header

FastAPI가 Spring Boot를 호출할 때 다음 header를 포함한다.

```http
X-Agent-Run-Id: run_20260601_001
X-Agent-Step-Id: step_006
X-Agent-Name: Executor
X-Request-Id: req_20260601_001
X-Actor-Type: agent
X-Actor-Id: bifrost-agent
```

Mutation 요청은 추가로 다음 header를 포함한다.

```http
X-Idempotency-Key: idem_20260601_001
```

### 4. 공통 Response Envelope

성공:

```json
{
  "ok": true,
  "request_id": "req_20260601_001",
  "operation": "get_consumer_lag",
  "result": {
    "status": "success",
    "summary": "orders-consumer lag is elevated on partition 3"
  },
  "evidence": [
    {
      "evidence_id": "ev_metric_001",
      "store_ref": "evidence://run_20260601_001/ev_metric_001",
      "summary": "consumer lag p95 increased from 120 to 4200",
      "redaction_status": "redacted"
    }
  ],
  "audit_event_id": "audit_20260601_001"
}
```

실패:

```json
{
  "ok": false,
  "request_id": "req_20260601_003",
  "operation": "scale_consumer_deployment",
  "error": {
    "code": "APPROVAL_REQUIRED",
    "message": "scale_consumer_deployment requires approval",
    "retryable": false,
    "required_action": "request_human_approval"
  },
  "audit_event_id": "audit_20260601_003"
}
```

Mutation 성공 응답은 before/after evidence reference를 포함해야 한다.

### 5. 표준 Error Code

| Code | 설명 |
| --- | --- |
| `VALIDATION_FAILED` | 요청 schema 또는 parameter 오류 |
| `POLICY_DENIED` | 정책상 금지 |
| `APPROVAL_REQUIRED` | 사람 승인 필요 |
| `APPROVAL_EXPIRED` | 승인 만료 |
| `APPROVAL_SCOPE_MISMATCH` | 승인 scope와 실행 요청 불일치 |
| `CHANGE_TICKET_REQUIRED` | 변경관리 티켓 필요 |
| `CHANGE_WINDOW_CLOSED` | 실행 window가 아님 |
| `RESOURCE_NOT_FOUND` | 요청 resource 없음 |
| `RESOURCE_NOT_OWNED_BY_PROJECT` | project 소유 resource가 아님 |
| `CONFLICT` | resource 상태 또는 idempotency 충돌 |
| `IDEMPOTENCY_REPLAY` | 기존 mutation 결과 replay |
| `TIMEOUT` | 외부 runtime 호출 timeout |
| `TRANSIENT_ERROR` | 일시적 오류 |
| `PERMISSION_DENIED` | service/user 권한 없음 |

### 6. System API

| Method | Path | 설명 |
| --- | --- | --- |
| `GET` | `/internal/ops/health` | Spring Boot Operations Backend 생존 상태를 확인한다. |
| `GET` | `/internal/ops/ready` | Kafka, Kubernetes, Prometheus, Loki/Log Store, Tempo, Evidence Store 연결 준비 상태를 확인한다. |
| `GET` | `/internal/ops/version` | backend version, API version, tool catalog version을 반환한다. |

### 7. Project / Resource Registry API

| Method | Path | 설명 |
| --- | --- | --- |
| `GET` | `/internal/ops/projects/{project_id}` | project metadata와 운영 scope를 조회한다. |
| `GET` | `/internal/ops/projects/{project_id}/resources` | project가 소유하거나 조회 가능한 resource 목록을 반환한다. |
| `GET` | `/internal/ops/projects/{project_id}/pipelines` | project pipeline 목록을 조회한다. |
| `GET` | `/internal/ops/projects/{project_id}/pipelines/{pipeline_id}` | pipeline topology와 dependency 정보를 조회한다. |
| `GET` | `/internal/ops/projects/{project_id}/dependencies` | source/sink dependency 목록을 조회한다. |
| `GET` | `/internal/ops/projects/{project_id}/dependencies/{dependency_id}` | dependency metadata와 ownership을 조회한다. |

### 8. Observability API

| Method | Path | 설명 |
| --- | --- | --- |
| `POST` | `/internal/ops/projects/{project_id}/observability/logs/search` | 로그를 검색하고 evidence reference를 반환한다. |
| `POST` | `/internal/ops/projects/{project_id}/observability/metrics/query` | Prometheus 등 metric backend를 query한다. |
| `GET` | `/internal/ops/projects/{project_id}/observability/events/k8s` | Kubernetes event를 조회한다. |
| `GET` | `/internal/ops/projects/{project_id}/observability/traces` | trace summary를 조회한다. |
| `GET` | `/internal/ops/projects/{project_id}/observability/alerts` | project 관련 alert 목록을 조회한다. |
| `GET` | `/internal/ops/projects/{project_id}/observability/alerts/{alert_id}` | alert 상세와 source reference를 조회한다. |

#### `POST /internal/ops/projects/{project_id}/observability/logs/search`

요청:

```json
{
  "pipeline_id": "daily_user_sync",
  "query": "ConnectionTimeout",
  "from": "2026-06-01T00:00:00Z",
  "to": "2026-06-01T01:00:00Z",
  "limit": 100
}
```

### 9. Pipeline API

| Method | Path | 설명 | 정책 |
| --- | --- | --- | --- |
| `GET` | `/internal/ops/projects/{project_id}/pipelines/{pipeline_id}/recent-changes` | 배포, config, image, connector 변경 이력을 조회한다. | read |
| `GET` | `/internal/ops/projects/{project_id}/pipelines/{pipeline_id}/tasks` | pipeline task 목록과 최근 상태를 조회한다. | read |
| `GET` | `/internal/ops/projects/{project_id}/pipelines/{pipeline_id}/tasks/{task_id}/status` | 특정 task 상태와 retry 이력을 조회한다. | read |
| `POST` | `/internal/ops/projects/{project_id}/pipelines/{pipeline_id}/pause` | pipeline 실행을 일시 중지한다. | approval |
| `POST` | `/internal/ops/projects/{project_id}/pipelines/{pipeline_id}/resume` | 일시 중지된 pipeline을 재개한다. | approval |
| `POST` | `/internal/ops/projects/{project_id}/pipelines/{pipeline_id}/backfills` | backfill 작업을 생성한다. | change management |
| `POST` | `/internal/ops/projects/{project_id}/pipelines/{pipeline_id}/rollbacks` | rollback 작업을 생성한다. | change management |

### 10. Dependency API

| Method | Path | 설명 | 정책 |
| --- | --- | --- | --- |
| `GET` | `/internal/ops/projects/{project_id}/dependencies/{dependency_id}/connection-status` | source/sink dependency의 reachability, timeout, error rate를 조회한다. | read |
| `GET` | `/internal/ops/projects/{project_id}/dependencies/{dependency_id}/latency` | dependency read/write latency summary를 조회한다. | read |
| `GET` | `/internal/ops/projects/{project_id}/dependencies/{dependency_id}/recent-changes` | credential, endpoint, schema 등 dependency 관련 변경 이력을 조회한다. | read |

이 API는 고객사 DB 내부 쿼리나 튜닝을 수행하지 않는다.

### 11. Kafka Cluster API

| Method | Path | 설명 | 정책 |
| --- | --- | --- | --- |
| `GET` | `/internal/ops/projects/{project_id}/kafka/clusters` | 접근 가능한 Kafka cluster 목록을 조회한다. | read |
| `GET` | `/internal/ops/projects/{project_id}/kafka/clusters/{cluster_id}` | Kafka cluster version, listener, health를 조회한다. | read |
| `GET` | `/internal/ops/projects/{project_id}/kafka/brokers` | broker 목록과 상태를 조회한다. | read |
| `GET` | `/internal/ops/projects/{project_id}/kafka/brokers/{broker_id}/metrics` | broker별 CPU, disk, network, request latency를 조회한다. | read |

### 12. Kafka Topic API

| Method | Path | 설명 | 정책 |
| --- | --- | --- | --- |
| `GET` | `/internal/ops/projects/{project_id}/kafka/topics` | topic 목록을 조회한다. | read |
| `GET` | `/internal/ops/projects/{project_id}/kafka/topics/{topic_name}` | topic partition, replication, config를 조회한다. | read |
| `GET` | `/internal/ops/projects/{project_id}/kafka/topics/{topic_name}/metrics` | topic ingress, egress, partition skew 지표를 조회한다. | read |
| `POST` | `/internal/ops/projects/{project_id}/kafka/topics` | KafkaTopic CR 기반 topic 생성을 요청한다. | change management |
| `PATCH` | `/internal/ops/projects/{project_id}/kafka/topics/{topic_name}/config` | topic config 변경을 요청한다. | change management |

Topic delete API는 제공하지 않는다.

### 13. Kafka Consumer Group API

| Method | Path | 설명 | 정책 |
| --- | --- | --- | --- |
| `GET` | `/internal/ops/projects/{project_id}/kafka/consumer-groups` | consumer group 목록을 조회한다. | read |
| `GET` | `/internal/ops/projects/{project_id}/kafka/consumer-groups/{consumer_group}` | consumer group member와 assignment를 조회한다. | read |
| `GET` | `/internal/ops/projects/{project_id}/kafka/consumer-groups/{consumer_group}/lag` | consumer lag와 offset progression을 조회한다. | read |
| `GET` | `/internal/ops/projects/{project_id}/kafka/consumer-groups/{consumer_group}/rebalance-events` | rebalance 이벤트 요약을 조회한다. | read |

Offset reset API는 v1에서 제공하지 않는다. 필요 시 change management 대상으로 별도 설계한다.

### 14. Kafka Connect API

| Method | Path | 설명 | 정책 |
| --- | --- | --- | --- |
| `GET` | `/internal/ops/projects/{project_id}/kafka/connect-clusters` | Kafka Connect cluster 목록과 health를 조회한다. | read |
| `GET` | `/internal/ops/projects/{project_id}/kafka/connectors` | connector 목록을 조회한다. | read |
| `GET` | `/internal/ops/projects/{project_id}/kafka/connectors/{connector_name}/status` | connector와 task 상태를 조회한다. | read |
| `GET` | `/internal/ops/projects/{project_id}/kafka/connectors/{connector_name}/config` | redacted connector config를 조회한다. | read |
| `GET` | `/internal/ops/projects/{project_id}/kafka/connectors/{connector_name}/tasks/{task_id}/trace` | task failure trace summary를 조회한다. | read |
| `POST` | `/internal/ops/projects/{project_id}/kafka/connectors/{connector_name}/tasks/{task_id}/restart` | connector task를 재시작한다. | approval |
| `POST` | `/internal/ops/projects/{project_id}/kafka/connectors/{connector_name}/restart` | connector 전체 재시작을 요청한다. | approval |
| `POST` | `/internal/ops/projects/{project_id}/kafka/connectors/{connector_name}/pause` | connector를 일시 중지한다. | approval |
| `POST` | `/internal/ops/projects/{project_id}/kafka/connectors/{connector_name}/resume` | connector를 재개한다. | approval |
| `PATCH` | `/internal/ops/projects/{project_id}/kafka/connectors/{connector_name}/config` | connector config 변경을 요청한다. | change management |

### 15. Kafka User / ACL API

| Method | Path | 설명 | 정책 |
| --- | --- | --- | --- |
| `GET` | `/internal/ops/projects/{project_id}/kafka/users` | KafkaUser 목록과 권한 summary를 조회한다. | read |
| `GET` | `/internal/ops/projects/{project_id}/kafka/users/{user_name}` | 특정 KafkaUser의 redacted 상태를 조회한다. | read |
| `POST` | `/internal/ops/projects/{project_id}/kafka/users` | KafkaUser 생성을 요청한다. | change management |
| `PATCH` | `/internal/ops/projects/{project_id}/kafka/users/{user_name}/acls` | ACL 변경을 요청한다. | change management |

Secret 원문은 반환하지 않는다.

### 16. Kubernetes API

| Method | Path | 설명 | 정책 |
| --- | --- | --- | --- |
| `GET` | `/internal/ops/projects/{project_id}/k8s/namespaces` | project가 접근 가능한 namespace 목록을 조회한다. | read |
| `GET` | `/internal/ops/projects/{project_id}/k8s/namespaces/{namespace}/deployments` | deployment 목록을 조회한다. | read |
| `GET` | `/internal/ops/projects/{project_id}/k8s/namespaces/{namespace}/deployments/{deployment_name}/health` | deployment replica, rollout, condition 상태를 조회한다. | read |
| `GET` | `/internal/ops/projects/{project_id}/k8s/namespaces/{namespace}/pods` | pod 목록을 조회한다. | read |
| `GET` | `/internal/ops/projects/{project_id}/k8s/namespaces/{namespace}/pods/{pod_name}/status` | pod 상태, restart count, container state를 조회한다. | read |
| `GET` | `/internal/ops/projects/{project_id}/k8s/namespaces/{namespace}/pods/{pod_name}/logs` | pod 로그를 redaction 후 evidence로 저장한다. | read |
| `GET` | `/internal/ops/projects/{project_id}/k8s/namespaces/{namespace}/events` | namespace event를 조회한다. | read |
| `GET` | `/internal/ops/projects/{project_id}/k8s/namespaces/{namespace}/pvcs` | PVC 상태와 사용량 summary를 조회한다. | read |
| `GET` | `/internal/ops/projects/{project_id}/k8s/namespaces/{namespace}/pvcs/{pvc_name}/status` | 특정 PVC 사용량과 condition을 조회한다. | read |
| `POST` | `/internal/ops/projects/{project_id}/k8s/namespaces/{namespace}/deployments/{deployment_name}/scale` | deployment replica 수를 변경한다. | approval |
| `POST` | `/internal/ops/projects/{project_id}/k8s/namespaces/{namespace}/deployments/{deployment_name}/rollout/restart` | deployment rollout restart를 요청한다. | approval |
| `POST` | `/internal/ops/projects/{project_id}/k8s/namespaces/{namespace}/deployments/{deployment_name}/rollback` | deployment rollback을 요청한다. | change management |

Pod exec, arbitrary manifest apply, delete API는 제공하지 않는다.

### 17. Strimzi / Rebalance API

| Method | Path | 설명 | 정책 |
| --- | --- | --- | --- |
| `GET` | `/internal/ops/projects/{project_id}/strimzi/kafkas` | Kafka CR 목록과 readiness를 조회한다. | read |
| `GET` | `/internal/ops/projects/{project_id}/strimzi/kafkas/{cluster_name}` | Kafka CR status와 listeners를 조회한다. | read |
| `GET` | `/internal/ops/projects/{project_id}/strimzi/kafkanodepools` | KafkaNodePool 목록과 role/replica 상태를 조회한다. | read |
| `GET` | `/internal/ops/projects/{project_id}/strimzi/kafkaconnects` | KafkaConnect CR 목록과 status를 조회한다. | read |
| `GET` | `/internal/ops/projects/{project_id}/strimzi/kafkaconnectors` | KafkaConnector CR 목록과 status를 조회한다. | read |
| `POST` | `/internal/ops/projects/{project_id}/rebalances` | KafkaRebalance proposal을 생성한다. | approval |
| `GET` | `/internal/ops/projects/{project_id}/rebalances/{rebalance_name}` | KafkaRebalance status와 proposal summary를 조회한다. | read |
| `POST` | `/internal/ops/projects/{project_id}/rebalances/{rebalance_name}/approve` | KafkaRebalance approve annotation을 적용한다. | approval |
| `POST` | `/internal/ops/projects/{project_id}/rebalances/{rebalance_name}/refresh` | KafkaRebalance proposal refresh를 요청한다. | approval |

### 18. Schema Registry API

| Method | Path | 설명 | 정책 |
| --- | --- | --- | --- |
| `GET` | `/internal/ops/projects/{project_id}/schema/subjects` | schema subject 목록을 조회한다. | read |
| `GET` | `/internal/ops/projects/{project_id}/schema/subjects/{subject}/versions` | subject version 목록을 조회한다. | read |
| `GET` | `/internal/ops/projects/{project_id}/schema/subjects/{subject}/changes` | subject 변경 이력을 조회한다. | read |
| `POST` | `/internal/ops/projects/{project_id}/schema/subjects/{subject}/compatibility/check` | schema compatibility를 검증한다. | read |

Schema 강제 변경 API는 v1에서 제공하지 않는다.

### 19. Approval API

| Method | Path | 설명 | 정책 |
| --- | --- | --- | --- |
| `POST` | `/internal/ops/projects/{project_id}/approvals` | action 실행을 위한 approval request를 생성한다. | low |
| `GET` | `/internal/ops/projects/{project_id}/approvals` | approval 목록을 조회한다. | read |
| `GET` | `/internal/ops/projects/{project_id}/approvals/{approval_id}` | approval 상세와 params hash를 조회한다. | read |
| `POST` | `/internal/ops/projects/{project_id}/approvals/{approval_id}/decision` | approval 승인 또는 거절을 기록한다. | approver |
| `POST` | `/internal/ops/projects/{project_id}/approvals/{approval_id}/validate` | 실행 직전 approval 유효성을 검증한다. | internal |

> **Approval source of truth = Spring Boot.** approval record(상태·params hash·승인자·만료·single-use)의 원본은 이 테이블이다. FastAPI의 `/api/v1/approvals/**`는 프론트용 facade로, 이 내부 API를 호출하고 run 연계 메타데이터(run_id↔approval_id, UI 표시용)만 보관한다. 승인 결정·검증·감사는 모두 Spring Boot가 집행한다([FastAPI Server Design §9 Persistence](../design/backend-fastapi/principles.md#2-server-design)). 동일 approval을 양쪽에 중복 생성하지 않는다.

### 20. Change Management API

| Method | Path | 설명 | 정책 |
| --- | --- | --- | --- |
| `GET` | `/internal/ops/projects/{project_id}/change-tickets/{change_ticket_id}` | change ticket 상태와 승인 여부를 조회한다. | read |
| `POST` | `/internal/ops/projects/{project_id}/change-tickets/{change_ticket_id}/validate` | 실행 window, rollback plan, scope를 검증한다. | internal |
| `GET` | `/internal/ops/projects/{project_id}/change-windows` | 예정된 change window 목록을 조회한다. | read |

외부 변경관리 시스템이 있으면 이 API가 adapter 역할을 한다.

### 21. Workflow Support API

| Method | Path | 설명 | 정책 |
| --- | --- | --- | --- |
| `POST` | `/internal/ops/projects/{project_id}/notifications` | 운영자 알림을 생성하거나 외부 알림 adapter로 전달한다. | low |
| `GET` | `/internal/ops/projects/{project_id}/notifications/{notification_id}` | 알림 처리 상태를 조회한다. | read |
| `POST` | `/internal/ops/projects/{project_id}/escalations` | 고객사/플랫폼/운영자 escalation record를 생성한다. | low |
| `GET` | `/internal/ops/projects/{project_id}/escalations/{escalation_id}` | escalation 상태와 전달 evidence reference를 조회한다. | read |
| `POST` | `/internal/ops/projects/{project_id}/tickets` | 외부 티켓 시스템 연동용 ticket 생성을 요청한다. | low |
| `GET` | `/internal/ops/projects/{project_id}/tickets/{ticket_id}` | ticket 상태를 조회한다. | read |

Workflow support API는 runtime resource를 직접 변경하지 않는다. Evidence summary, owner, severity, related resource, requested follow-up만 저장하거나 외부 시스템에 전달한다.

### 22. Evidence API

| Method | Path | 설명 | 정책 |
| --- | --- | --- | --- |
| `POST` | `/internal/ops/projects/{project_id}/evidence` | 운영 조회 결과를 Evidence Store에 저장하고 reference를 만든다. | internal |
| `GET` | `/internal/ops/projects/{project_id}/evidence/{evidence_id}` | evidence metadata와 redaction 상태를 조회한다. | read |
| `POST` | `/internal/ops/projects/{project_id}/evidence/{evidence_id}/tombstone` | retention 또는 삭제 요청에 따라 tombstone을 기록한다. | approval |

Evidence raw content 조회는 기본 API로 제공하지 않는다.

### 23. Audit / Timeline API

| Method | Path | 설명 | 정책 |
| --- | --- | --- | --- |
| `GET` | `/internal/ops/projects/{project_id}/audit-events` | project audit event 목록을 조회한다. | read |
| `GET` | `/internal/ops/projects/{project_id}/audit-events/{audit_event_id}` | 특정 audit event 상세를 조회한다. | read |
| `GET` | `/internal/ops/projects/{project_id}/runs/{run_id}/timeline` | run과 연결된 backend action timeline을 조회한다. | read |
| `POST` | `/internal/ops/projects/{project_id}/audit-events` | FastAPI 또는 backend event를 audit log로 기록한다. | internal |

### 24. Report Support API

| Method | Path | 설명 | 정책 |
| --- | --- | --- | --- |
| `GET` | `/internal/ops/projects/{project_id}/incidents/{incident_id}/summary` | report에 필요한 incident summary를 조회한다. | read |
| `GET` | `/internal/ops/projects/{project_id}/incidents/{incident_id}/related-resources` | incident 관련 pipeline, connector, topic, deployment를 조회한다. | read |
| `GET` | `/internal/ops/projects/{project_id}/runs/{run_id}/execution-summary` | run에서 실행된 action과 결과 요약을 조회한다. | read |
| `PATCH` | `/internal/ops/projects/{project_id}/incidents/{incident_id}/rca` | **검증된 RCA 결과를 incident에 기록한다** (`root_cause_summary`, severity escalation, status 전이). | internal |

#### `PATCH /internal/ops/projects/{project_id}/incidents/{incident_id}/rca`

FastAPI는 Verifier가 `approved_for_final_response=true`로 승인한 RCA 결과만 이 endpoint로 기록한다(메타DB `incident.root_cause_summary`를 채우는 유일한 경로 — §4.3.7). severity는 [부록 B.7](../spec.md#b7-인시던트-자동-생성-및-그룹화-규칙) 기준으로 올리기만 하고 자동으로 낮추지 않으며, status 전이(`investigating`)는 HITL 조치 실행 결과를 따른다. 모든 변경은 audit event로 남는다.

```json
{
  "run_id": "run_20260601_001",
  "root_cause_summary": "source DB connection timeout 가능성이 가장 높음",
  "root_cause_id": "SOURCE_DB_CONNECTION_TIMEOUT",
  "confidence": 0.82,
  "severity": "CRITICAL",
  "status": "investigating"
}
```

### 25. Admin / Catalog API

| Method | Path | 설명 | 정책 |
| --- | --- | --- | --- |
| `GET` | `/internal/ops/admin/tool-catalog` | Spring Boot에 등록된 operation catalog를 조회한다. | admin |
| `GET` | `/internal/ops/admin/policies` | backend policy rule version을 조회한다. | admin |
| `POST` | `/internal/ops/admin/policies/reload` | policy cache를 reload한다. | admin |
| `GET` | `/internal/ops/admin/dependencies` | Kubernetes, Kafka, Prometheus client 상태를 조회한다. | admin |

### 26. 금지 API

다음 API는 만들지 않는다.

- pod exec
- shell command 실행
- arbitrary SQL 실행
- Secret 원문 조회
- topic delete
- PVC delete
- namespace delete
- connector config 임의 overwrite
- DB truncate 직접 실행
- LLM이 생성한 manifest 직접 apply

필요하면 변경관리 workflow에서 별도 runbook과 사람 실행으로 처리한다.

### 27. 호환성 메모

canonical prefix는 `/internal/ops/projects/{project_id}`다. 과거 문서의 `/ops` 또는 `/internal/tools/projects` 표기는 더 이상 기준으로 사용하지 않는다.

Agent 논리 tool 이름은 API path와 직접 같을 필요가 없다. 매핑은 [FastAPI DETAILS](../design/backend-fastapi/tool-catalog.md#4-tool-catalog)의 Tool Client Registry 표를 따른다.
