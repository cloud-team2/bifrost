# 시연 & 핵심 시나리오

> 이 문서는 **[spec.md](./spec.md)(기능명세서, SoT)** 의 FR을 데모 흐름으로 엮은 시나리오다. 기능·API·상태값·임계값의 정의는 [spec.md](./spec.md)·[design/](./design/)·[api/](./api/)가 단일 출처이며 여기서 중복 정의하지 않는다.
>
> **아키텍처**: 단일 콘솔(Frontend) → **operations-backend**(플랫폼 `/api/v1`, 프로비저닝·모니터링·정책·감사) + **FastAPI Agent**(AI 장애대응 `/api/v1/agent`, 운영 조치는 operations-backend `/internal/ops`로 위임). 상태 갱신은 **SSE**. 구 core/orchestrator는 단일 operations-backend로 통합되었다([ADR 0004](./adr/0004-monorepo-monolith.md)).

## 1. 핵심 시연 시나리오 (Critical Path)

| # | 단계 | FR | 핵심 |
|---|---|---|---|
| 1 | 로그인 | FR-001 | 데모 계정(`ta@bifrost.io`)으로 콘솔 진입 → WorkspaceListView |
| 2 | 워크스페이스 생성·선택 | FR-002 | 이름 입력 → **`projectKey` 슬러그 자동 생성** → **KafkaUser/ACL 자동 프로비저닝** → 이후 모든 데이터가 `workspace_id`로 scope |
| 3 | Database 등록 | FR-014 | 연결 정보 입력 → **연결 테스트**(`SELECT 1`, 5s, 오류 5종 분류) → 등록(자격증명은 **secretRef**만, 응답 `****`) |
| 4 | CDC 준비도 점검 | FR-015 | `wal_level`·replication 권한·slot·binlog 등 **OK/WARNING/BLOCKED** + hint. BLOCKED면 소스 선택 불가 |
| 5 | 파이프라인 생성 | FR-004 | 마법사: 연결방식(EDA/CDC) → Source DB → **테이블** → (CDC만)Sink DB → 이름 → `creating`→`active`(SSE 수신) |
| 6 | 데이터 흐름 | — | source INSERT/UPDATE/DELETE → **EDA**: 토픽 적재 / **CDC**: sink DB 반영 |
| 7 | 모니터링 | FR-006~009 | Overview(produce/consume·lag·error) · Consumers(파티션 lag) · Connector(상태·task·오류) · (CDC)Sync(행수·동기화율) |
| 8 | 메시지·매핑 | FR-010, FR-012 | Debezium before/after 페이로드 · 컬럼·타입 매핑 |
| 9 | 구독 가이드 | FR-011 | topic alias·bootstrap·group ID + 언어별(Java/Python/Node) 스니펫 복사 → 외부 Consumer 연결 |
| 10 | 운영·인시던트 | FR-021 | 인시던트 목록/상세(근본원인·영향 파이프라인·관련 이벤트 타임라인) |
| 11 | AI 장애대응 (HITL) | FR-022/025/026 | BifrostAgentPanel: 자연어 요청 또는 자동 감지 → 진단(RCA) → 추천 조치 → **사용자 승인(Run) 후 실행** |

> Kafka Topic/파티션/오프셋 등 인프라 세부는 화면에 노출하지 않는다(자동 처리). Topic 이름은 **Connection Guide 탭에서만** 노출한다.

## 2. 핵심 E2E 흐름

> 모든 단계는 단일 워크스페이스 scope이며, 상태 전이는 플랫폼 SSE(`pipeline_status_changed` 등)로 프론트에 푸시된다. 상세 계약은 [design/frontend.md](./design/frontend.md)·[api/springboot.md](./api/springboot.md).

### 2.1 워크스페이스 생성 + 자동 프로비저닝 (FR-002)

```text
Frontend → operations-backend: POST /api/v1/workspaces {name}
  1. metadb에 workspace 저장, 이름에서 projectKey 슬러그 생성, 생성자를 project_member로 등록
  2. provisioning(in-process, Fabric8): KafkaUser CR `proj-{projectKey}-user` apply
       → ACL: topic prefix `cdc.table.{projectKey}.*` read/write
       → Strimzi가 동명 Secret(SCRAM 자격증명) 자동 생성
  3. 응답 → 워크스페이스 선택 → PipelinesView
```

### 2.2 Database 등록 + CDC 준비도 (FR-014, FR-015)

```text
1. AddDatabaseModal: 연결 정보 입력
2. POST /api/v1/workspaces/{wsId}/databases/connection-test
     → 동적 HikariCP `SELECT 1`(5s). 실패 시 CONNECTION_REFUSED/AUTH_FAILED/DB_NOT_FOUND/TIMEOUT/UNKNOWN
3. POST /api/v1/workspaces/{wsId}/databases
     → password를 SecretStore.put() → metadb엔 secret_ref만 저장(평문·암호문 금지, 응답 ****)
4. (자동) CDC 준비도 점검: 엔진별 CdcReadinessChecker가 대상 DB 질의
     → PostgreSQL: wal_level=logical, REPLICATION 권한, max_replication_slots, publication
     → MariaDB: log_bin=ON, binlog_format=ROW, binlog_row_image=FULL, server_id, REPLICATION 권한
     → overallStatus(OK/WARNING/BLOCKED) + 항목별 hint 표시
```

### 2.3 파이프라인 생성 — EDA / CDC (FR-004, FR-005)

```text
1. CreatePipelineModal: 연결방식(EDA fan-out / CDC direct) → Source DB → 테이블 → (CDC)Sink DB → 이름
2. POST /api/v1/workspaces/{wsId}/pipelines {name, pattern, sourceDbId, sinkDbId?, schema, table}
     → metadb pipeline 저장(status: creating), 응답 {pipeline_id, status:"creating"}
3. provisioning(Fabric8): KafkaConnector CR apply (생성 시점에만 secretStore.resolve로 자격증명 주입)
     → EDA: Source Debezium 1개 (tasksMax=1) → 토픽 cdc.table.{projectKey}.{dbName}.{schema}.{table} 자동 생성
     → CDC: Source Debezium + Sink JDBC 1개 (tasksMax=3, upsert)
4. Watcher(Fabric8)가 KafkaConnector .status 변화 감지 → PipelineStatusService(단일 writer)
     → pipeline status 재계산(creating→active, 일부 task FAILED→lag) → event/audit 기록 → SSE push
5. 생명주기: pause/resume(state patch) · delete(CR 삭제 + 행 제거)
```

### 2.4 AI 장애대응 — 진단 → 승인 → 실행 (FR-022, FR-025, FR-026, HITL)

```text
1. 자동 감지(lag≥5,000·Connector FAILED 등) 또는 사용자 자연어 요청
     → Frontend → FastAPI: POST /api/v1/agent/runs {project_id, message, incident_id?}
2. FastAPI workflow(기본 diagnose_only): Router→Correlation→Planner→Retrieval→Classifier→RCA→Verifier→Report
     → 운영 조회는 operations-backend /internal/ops read tool로 위임(Agent는 K8s/Kafka 직접 접근 X)
     → 원문은 Evidence Store, State엔 reference만. 진행은 SSE(agent_started/tool_call_completed/...)
3. 조치 요청 시: Remediation(후보) → Policy Guard(allow/approval/change/deny)
     → approval_required → 사용자 "Run"(HITL) → POST /api/v1/approvals/{id}/decision
4. 실행: FastAPI → operations-backend /internal/ops (Spring이 정책·승인·params hash·idempotency 재검증 후 실행)
     → Verifier 검증 통과분만 Report로 사용자에게 노출. 검증된 RCA는 incident에 기록
```

## 3. 데모 합격선 (DoD)

- [ ] 로그인 → 워크스페이스 생성 시 KafkaUser/ACL 자동 프로비저닝
- [ ] **PostgreSQL·MariaDB 둘 다** 등록 + 연결 테스트 + CDC 준비도(OK/WARNING/BLOCKED) 표시
- [ ] **EDA·CDC 둘 다** 파이프라인 생성 → `creating`→`active`(SSE), 자격증명은 secretRef로만 보관
- [ ] source INSERT/UPDATE/DELETE → EDA 토픽 적재 / CDC sink 반영
- [ ] 모니터링 탭(Overview/Consumers/Connector/Sync)·메시지·구독 스니펫 동작
- [ ] 인시던트 자동 생성(임계 초과) + AlertsView 상세
- [ ] AI 장애대응: 자연어/자동감지 → 진단(RCA) → 추천 조치 → **HITL 승인 후 실행**(audit 기록)
- [ ] 2개 이상 워크스페이스 동시 사용 시 토픽·리소스 격리(다른 워크스페이스 토픽 접근 불가)

> 주차별 추진 일정·마일스톤은 [team/roadmap.md](./team/roadmap.md), 이번주 상세 작업은 [team/todo.md](./team/todo.md).
