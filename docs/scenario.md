# 시연 & 통합 시나리오

> 구 `mvp-spec.md`에서 **시나리오·완료기준·로드맵**을 분리한 문서다. 기능·API·상태값의 단일 출처(SoT)는 [spec.md](./spec.md) · [design/](./design/) · [api/](./api/)이며, 여기서는 중복 정의하지 않는다.
> 용어/구조는 모놀리스 정렬([ADR 0004](./adr/0004-monorepo-monolith.md)) 기준이다: 구 core/orchestrator는 단일 `operations-backend`.

## 1. 시연 시나리오 (Critical Path)

```
1. 사용자가 회원가입 → 자동 워크스페이스 프로비저닝 (KafkaUser, Namespace 등 자동 생성)
2. 대시보드에서 PostgreSQL 또는 MariaDB Database 등록
   → CDC readiness 자동 검사 → 결과 표시
3. 캔버스에서 Database 노드 클릭 → "파이프라인 생성" 버튼
   → 테이블 선택 위저드 → 파이프라인 이름 입력 → 생성
4. 캔버스에 Pipeline 엣지 등장 (PENDING → RUNNING)
5. 사용자 DB에 데이터 INSERT/UPDATE/DELETE → Kafka 토픽에 메시지 도착
6. 사용자가 Consumer App 연결 (UI에서 제공한 코드 스니펫 사용)
7. 캔버스에 Service 노드 자동 등장
8. (Stretch goal) 채팅으로 같은 작업 가능
```


## 2. 통합 시나리오 (대시보드 모드)

> 아래 흐름의 `core`/`orchestrator` 단계는 모놀리스 통합 전 표기다([ADR 0004](./adr/0004-monorepo-monolith.md)). 현재는 모두 **`operations-backend` 한 프로세스 안의 패키지 호출**이며, `core → orchestrator: POST /internal/pipelines`나 `orchestrator → Kafka 이벤트 → core 구독`은 **provisioning 호출 + watcher → `PipelineStatusService`(in-process)** 로 대체된다.

### 시나리오 1: 회원가입 + 자동 프로비저닝

```
1. Frontend → core: POST /api/auth/register
2. core가 MetaDB에 workspace + user 저장
3. core → orchestrator: POST /internal/workspaces/provision
4. orchestrator가 K8s 작업:
   - Namespace 생성
   - KafkaUser CRD 생성 (Strimzi가 비밀번호 + Secret 자동 생성)
   - ResourceQuota, NetworkPolicy 적용
5. orchestrator 응답 → core
6. core가 JWT 발급 → Frontend
7. Frontend가 토큰 저장 → 메인 화면으로
```

### 시나리오 2: Database 등록 (PG)

```
1. 사용자가 위저드에서 PG 정보 입력 + 비밀번호 + dbType = POSTGRESQL
2. Frontend → core: POST /api/databases
3. core가 K8s Secret 생성 (acme-ds-{id}-creds)
4. core가 MetaDB에 database 저장 (status: CHECKING)
5. core가 201 즉시 응답
6. (백그라운드) PostgresInspector가 사용자 PG에 JDBC 연결
7. testConnection() → checkCDCReadiness()
   - wal_level 확인
   - REPLICATION 권한 확인
   - max_replication_slots 확인
   - max_wal_senders 확인
8. core가 MetaDB의 database 업데이트 (status: GREEN/YELLOW/RED + 상세 리포트)
9. core → WebSocket Frontend: DATASOURCE_INSPECTED
10. Frontend 캔버스에 Database 노드 등장 + readiness 색상 표시
```

### 시나리오 3: Database 등록 (MariaDB)

```
1-5: 위와 동일, 단 dbType = MARIADB
6. (백그라운드) MariaDBInspector가 MariaDB에 JDBC 연결
7. checkCDCReadiness():
   - SHOW VARIABLES LIKE 'log_bin' → ON
   - SHOW VARIABLES LIKE 'binlog_format' → ROW
   - SHOW VARIABLES LIKE 'binlog_row_image' → FULL
   - SHOW VARIABLES LIKE 'server_id' → 0보다 큰 값
   - SHOW GRANTS FOR CURRENT_USER → REPLICATION SLAVE, REPLICATION CLIENT 포함
8-10: 위와 동일
```

### 시나리오 4: Pipeline 생성 (대시보드)

```
1. 사용자가 캔버스에서 Database 노드 클릭 → "파이프라인 생성"
2. 위저드: 이름 입력 → 테이블 선택 → 확인
3. Frontend → core: POST /api/pipelines
4. core 처리:
   a. Database 조회, cdcReadiness == GREEN 확인
   b. DatabaseInspectorFactory.create(database) → 적절한 Inspector
   c. inspector.generateConnectorConfig() → Debezium config JSON
   d. MetaDB에 pipeline 저장 (status: PENDING)
   e. orchestrator → POST /internal/pipelines
5. orchestrator 처리:
   a. KafkaTopic CRD 생성 (Strimzi가 토픽 자동 생성)
   b. KafkaConnector CRD 생성 (Strimzi가 Connect에 등록)
   c. 즉시 202 응답
6. core가 202 응답 → Frontend
7. Frontend 캔버스에 Pipeline 엣지(또는 노드) PENDING 상태로 등장
8. (비동기) Strimzi가 KafkaConnector reconcile
9. (비동기) Kafka Connect가 Debezium task 시작
10. orchestrator watch가 status RUNNING 감지
11. orchestrator → Kafka 토픽 발행 (CONNECTOR_STATUS_CHANGED)
12. core가 토픽 구독 → MetaDB pipeline 업데이트
13. core → WebSocket Frontend: PIPELINE_STATUS_CHANGED
14. Frontend 캔버스가 RUNNING으로 갱신 (색상/아이콘 변경)
15. 사용자가 Pipeline 노드 클릭 → 상세 패널에서 Consumer 연결 정보 확인
```

### 시나리오 5: 컨슈머 자동 발견

```
1. 사용자가 Consumer App 코드 작성:
   - bootstrap.servers, group.id, SASL/SCRAM 자격증명 입력
   - 토픽 구독
2. App이 Kafka 접속 → SASL 인증
3. Kafka가 ACL 검증 (proj-acme.* 토픽만 read 가능)
4. App이 토픽 구독 → Consumer Group 등록됨
5. (백그라운드 30초 주기) orchestrator가 Admin API로 Consumer Group 조회
6. 새 그룹 "payment-service" 발견 → SERVICE_DISCOVERED 이벤트 발행
7. core가 구독 → MetaDB discovered_services 저장
8. core → WebSocket Frontend
9. Frontend 캔버스에 Service 노드 등장
10. Pipeline의 토픽과 연결되는 엣지도 자동 그어짐
```

---

## 3. Sprint 일정

### Sprint 1 (Week 1-2): 인프라 + 스켈레톤

| 사람 | 작업 |
| --- | --- |
| A | EKS + Strimzi + Kafka + Connect (PG/MariaDB Debezium plugin 포함) |
| A | ECR 3개 리포지토리, 3개 서비스 배포 매니페스트 |
| B | operations-backend 스켈레톤, 인증 API, MetaDB Flyway |
| B | `DatabaseInspector` 인터페이스 + DTO 정의 PR |
| C | operations-backend 스켈레톤, fabric8 PoC |
| C | TenantProvisioner 구현 시작 |
| D | (기다림 또는 도구 정의 사전 작업) |
| E | Frontend 스켈레톤, 로그인 화면 (mock API) |

**마일스톤**: 각 서비스 Pod 가동, 수동 KafkaConnector yaml로 CDC 동작 확인

### Sprint 2 (Week 3-4): Database + Inspector

| 사람 | 작업 |
| --- | --- |
| A | Helm chart, CI/CD 파이프라인 동작 |
| A | 모니터링 스택 (Prometheus, Grafana) |
| B | PostgresInspector 완성 (testConnection, listTables, checkCDCReadiness) |
| B | MariaDBInspector 완성 |
| B | Database API (POST, GET, DELETE) + K8s Secret 생성 |
| B | CDC readiness 백그라운드 검사 |
| C | TenantProvisioner 완성 (Namespace, KafkaUser, Quota, NetPol) |
| C | ConnectorManager 구현 (KafkaConnector + KafkaTopic CRD 적용) |
| C | 회원가입 시 프로비저닝 통합 테스트 |
| D | (대기 또는 도구 정의 준비) |
| E | 로그인/회원가입 화면, Database 위저드 (실제 API 연동) |
| E | 캔버스 기본 (Database 노드 표시) |

**마일스톤**: PG/MariaDB Database 등록 → CDC readiness GREEN 확인 → 캔버스에 표시

### Sprint 3 (Week 5-6): Pipeline + 실시간

| 사람 | 작업 |
| --- | --- |
| A | 운영 자동화, alerting |
| B | Pipeline API (POST, GET, DELETE) |
| B | Inspector.generateConnectorConfig() 완성 (PG + MariaDB) |
| B | Kafka 이벤트 구독 (CONNECTOR_STATUS_CHANGED, SERVICE_DISCOVERED) |
| B | WebSocket 푸시 |
| C | ConnectorManager + TopicManager 완성 |
| C | Connector status watcher (K8s watch → Kafka 이벤트) |
| C | ConsumerGroupDiscoverer (Admin API 폴링 → Kafka 이벤트) |
| C | DELETE 흐름 (CRD 삭제) |
| D | (시간 되면 ai-service 셋업 시작) |
| E | Pipeline 위저드 (테이블 선택) |
| E | 캔버스 실시간 갱신 (WebSocket 연동) |
| E | Pipeline 상세 패널 (Consumer 연결 정보) |
| E | Service 노드 표시 |

**마일스톤**: 대시보드만으로 처음부터 끝까지 동작 (회원가입 → DB 등록 → Pipeline 생성 → 데이터 흐름 → Service 자동 발견)

### Sprint 4 (Week 7-8): 채팅 + 자연어 (Stretch)

| 사람 | 작업 |
| --- | --- |
| A | 카오스 테스트 |
| B | 채팅 메시지 저장 API, ai-service 호출 forwarding |
| C | (안정화, 디버깅) |
| D | ai-service 구현: LLM 통합, 도구 정의, SSE 스트리밍 |
| D | core의 도구 호출 forwarding (userToken pass-through) |
| E | 채팅 패널 UI |
| E | 채팅 도구 호출 진행 상황 시각화 |

**마일스톤**: "주문 DB CDC 만들어줘" 한 줄로 파이프라인 생성

### Sprint 5 (Week 9-10): 통합 + 시연

전원이 E2E 테스트, 버그 수정, 발표 준비.

---

## 4. 완료 정의 (DoD)

MVP가 끝났다고 말할 수 있는 기준:

- [ ] 신규 사용자가 회원가입 → 5분 내 첫 CDC 파이프라인 생성 가능
- [ ] **PostgreSQL과 MariaDB 둘 다** Database 등록 + CDC 동작
- [ ] 사용자 DB의 INSERT/UPDATE/DELETE가 Kafka 토픽에 도착
- [ ] 컨슈머 연결 시 캔버스에 Service 노드 자동 등장
- [ ] 2개 이상의 워크스페이스 동시 사용해도 데이터/리소스 격리 (한 워크스페이스가 다른 토픽 못 봄)
- [ ] Pipeline 삭제 시 K8s 리소스 + 토픽 정리
- [ ] (Stretch) 채팅으로 같은 흐름 가능

---

## 5. 결정해야 할 항목

이거 다음 회의에 정해야 함:

1. **공통 DTO 모듈**: `common-dto` Gradle 모듈로 분리 vs 각 서비스 자체 정의
2. **에러 코드 체계**: HTTP 상태 + 비즈니스 코드 형식
3. **서비스 간 인증**: 단순 공유 토큰 vs mTLS
4. **로깅 포맷**: JSON, 필수 필드 (traceId, workspaceId, userId)
5. **API 문서**: SpringDoc OpenAPI 활성화 (자동 TS client 생성용)
6. **로컬 개발 환경**: Docker Compose 구성
7. **테스트 DB**: Testcontainers 표준화

---
