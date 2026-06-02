# MVP 구현 명세서 v2

**목적**: 캡스join 시연 가능한 최소 기능 정의
**범위 결정** (이 버전에서):
- ✅ PostgreSQL + MariaDB 둘 다 지원
- ✅ 대시보드(폼) 기반 직접 구성 우선
- ⏳ 자연어/채팅은 후순위 (Sprint 4)
- ✅ CDC만 (SYNC는 Phase 2)

---

## 0. 시연 시나리오 (Critical Path)

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

---

## 1. 보안 / 멀티 테넌시 설계 (핵심)

### 왜 KafkaUser + Secret이 필요한가

#### KafkaUser
- 목적: 사용자의 Consumer App이 우리 Kafka에 접속할 때 인증/인가
- 없으면: 익명 접속 허용 → 멀티 테넌시 격리 깨짐 (다른 워크스페이스 토픽 읽기 가능)
- 동작: Strimzi가 KafkaUser CRD 받으면 Kafka에 SCRAM 사용자 등록 + ACL 적용 + 자격증명 Secret 자동 생성

#### Secret (두 가지 용도)

**Secret A: 사용자 DB credentials** (우리가 명시적으로 만듦)
- 사용자가 Database 등록 시 입력한 PG/MariaDB 비밀번호 저장
- KafkaConnector CRD가 참조해서 Debezium이 사용자 DB 접속 시 사용
- 이유: MetaDB에 평문 저장 안 하기 위해. K8s native 패턴.

**Secret B: KafkaUser 자격증명** (Strimzi가 자동 생성)
- Strimzi가 KafkaUser 만들 때 비밀번호 랜덤 생성해서 Secret으로 저장
- core가 사용자에게 "Kafka 접속 정보" 알려줄 때 여기서 읽음
- 우리 코드가 명시적으로 만들지 않음 (KafkaUser의 부산물)

### 워크스페이스 자동 생성되는 K8s 리소스

| 리소스 | 시점 | 누가 만듦 |
| --- | --- | --- |
| Namespace `proj-{projectKey}` | 회원가입 시 1회 | operations-backend(provisioning) |
| KafkaUser (consumer용) | 회원가입 시 1회 | operations-backend(provisioning) |
| Secret (KafkaUser 비번) | KafkaUser 생성 시 자동 | Strimzi |
| ResourceQuota | 회원가입 시 1회 | operations-backend(provisioning) |
| NetworkPolicy | 회원가입 시 1회 | operations-backend(provisioning) |

| 리소스 | 시점 | 누가 만듦 |
| --- | --- | --- |
| Secret (DB 자격증명) | Database 등록 시 | operations-backend (Database당 1개) |

| 리소스 | 시점 | 누가 만듦 |
| --- | --- | --- |
| KafkaConnector | Pipeline 생성 시 | operations-backend(provisioning) |
| KafkaTopic | Pipeline 생성 시 | operations-backend(provisioning) |

### 토픽 네이밍 + ACL 규칙

```
토픽 이름: cdc.table.{projectKey}.{dbName}.{schema}.{table}
예: cdc.table.acme.shop.public.orders

KafkaUser ACL:
- proj-{projectKey}-user 사용자는
- topic prefix "cdc.table.{projectKey}." 에 대해서만 Read/Describe 권한
- 다른 워크스페이스 토픽은 접근 불가
```

---

## 2. 서비스 구성

> **📌 모놀리스 정렬 노트 ([ADR 0004](../adr/0004-monorepo-monolith.md))**: 구 core/orchestrator는 단일 모놀리스 **`operations-backend`** 로 통합되었다. B(도메인)·C(provisioning/watcher)는 같은 모놀리스의 다른 패키지이며, 둘 사이의 REST/Kafka는 in-process 호출이다. 용어는 design 기준(`workspace`/`database`/`projectKey`), `ai-service`는 FastAPI.

### 2.1 서비스 구성

| 서비스 | 담당 | 포트 | 외부 노출 |
| --- | --- | --- | --- |
| **operations-backend** (Spring 모놀리스) | B·C | 8080 | ✅ `/api/v1` (+ 내부 `/internal/ops`) |
| **ai-service** (FastAPI) | D | 8082 | ❌ 내부 (Sprint 4에 합류) |
| **frontend** | E | 80 | ✅ |

### 2.2 통신 흐름 (대시보드 모드)

```
[Frontend] ─REST+WS─→ [operations-backend] ─Fabric8/K8s API─→ [K8s/Strimzi]
                          │  (provisioning·watcher 모두 in-process)
                          ├─JDBC─→ [사용자 DB] (Inspector로 직접 연결)
                          └─watcher: KafkaConnector status → PipelineStatusService → WS→ [Frontend]
```

자연어 모드 (Sprint 4 추가):
```
[Frontend] ─SSE─→ [operations-backend] ─위임─→ [ai-service(FastAPI)]
                                                   │
                                                   └─도구 호출─→ [operations-backend /internal/ops]
```

---

## 3. operations-backend

### 3.1 책임

- 인증 (JWT 발급/검증)
- 회원가입 시 워크스페이스 프로비저닝 (provisioning 패키지, in-process)
- Database CRUD (PG + MariaDB)
- Inspector 모듈 내장 (사용자 DB JDBC 검사)
- Pipeline CRUD (provisioning 패키지 호출, in-process)
- 자동 발견된 Service 관리
- WebSocket 실시간 푸시

### 3.2 핵심 기능 (Sprint 순서대로)

| Sprint | 기능 |
| --- | --- |
| 1 | 회원가입/로그인, JWT |
| 2 | Database 등록 + Inspector (PG + MariaDB) |
| 2 | CDC readiness 검사 |
| 3 | Pipeline 생성/조회/삭제 |
| 3 | WebSocket 이벤트 푸시 |
| 3 | watcher 상태 반영 → PipelineStatusService (in-process) |
| 3 | 자동 발견된 Service 조회 |
| 4 | 채팅 (ai-service에 위임) |

### 3.3 REST API 명세 (대시보드용)

> 모든 인증 필요 API는 `Authorization: Bearer <jwt>` 헤더 필수.

#### 인증

##### POST /api/auth/register
```json
요청:
{
  "email": "user@example.com",
  "password": "password123",
  "workspaceName": "Acme Corp"
}

응답 201:
{
  "userId": "uuid",
  "workspaceId": "uuid",
  "workspaceName": "Acme Corp",
  "token": "eyJhbGc..."
}

내부 흐름:
- MetaDB에 workspace + user 저장
- provisioning 패키지 호출 → Namespace, KafkaUser, Quota, NetPol (in-process)
- 응답 대기 후 JWT 발급
- 프로비저닝 실패 시 롤백
```

##### POST /api/auth/login
```json
요청: { "email": "...", "password": "..." }
응답 200: { "userId": "...", "workspaceId": "...", "token": "..." }
```

##### GET /api/auth/me
```json
응답 200:
{
  "userId": "...",
  "workspaceId": "...",
  "workspaceName": "...",
  "email": "..."
}
```

#### Database

##### POST /api/databases
```json
요청:
{
  "name": "주문 DB",
  "dbType": "POSTGRESQL",        // POSTGRESQL | MARIADB
  "host": "user-db.example.com",
  "port": 5432,
  "dbName": "orders",
  "username": "debezium",
  "password": "..."
}

응답 201:
{
  "id": "uuid",
  "name": "주문 DB",
  "dbType": "POSTGRESQL",
  "host": "...",
  "port": 5432,
  "dbName": "orders",
  "username": "debezium",
  "cdcReadiness": { "status": "CHECKING" },
  "createdAt": "..."
}

내부 흐름:
- K8s Secret 생성 (proj-{projectKey}-ds-{databaseId}-creds)
- MetaDB에 database 저장 (secret_ref 포함)
- 백그라운드로 Inspector가 CDC readiness 검사
- 검사 완료 시 WebSocket으로 DATASOURCE_INSPECTED 이벤트 push
```

##### GET /api/databases
##### GET /api/databases/{id}
```json
응답 200:
{
  "id": "uuid",
  "name": "...",
  "dbType": "POSTGRESQL",
  "host": "...",
  ...
  "cdcReadiness": {
    "status": "RED",
    "checks": [
      {
        "key": "wal_level",
        "passed": false,
        "message": "wal_level is 'replica', required 'logical'",
        "remediation": "ALTER SYSTEM SET wal_level = 'logical'; Then restart PostgreSQL."
      },
      {
        "key": "replication_privilege",
        "passed": true,
        "message": "User has REPLICATION privilege",
        "remediation": null
      }
    ],
    "lastCheckedAt": "..."
  }
}
```

##### GET /api/databases/{id}/tables
```json
응답 200:
[
  {
    "schema": "public",
    "name": "orders",
    "approximateRowCount": 1500,
    "hasPrimaryKey": true,
    "columns": [
      { "name": "id", "type": "INTEGER", "nullable": false, "isPrimaryKey": true },
      ...
    ]
  }
]

비고:
- PostgreSQL: schema = pg schema (public, etc.)
- MariaDB: schema = database 이름
- 단일 응답에 전체 스키마+테이블+컬럼 (MVP 단순화)
```

##### POST /api/databases/{id}/recheck
```json
응답 202:
{
  "id": "uuid",
  "cdcReadiness": { "status": "CHECKING" }
}

비고:
- CDC readiness 재검사 트리거 (사용자가 DB 설정 고친 후 사용)
- 백그라운드 작업, 결과는 WebSocket으로 push
```

##### DELETE /api/databases/{id}
```json
응답 204
에러 409: 사용 중인 파이프라인 존재 시
```

#### Pipeline

##### POST /api/pipelines
```json
요청:
{
  "name": "orders-cdc",
  "sourceDatabaseId": "uuid",
  "tables": [
    { "schema": "public", "name": "orders" }
  ]
}

응답 202:
{
  "id": "uuid",
  "name": "orders-cdc",
  "type": "CDC",
  "status": "PENDING",
  "sourceDatabase": { "id": "...", "name": "주문 DB", "dbType": "POSTGRESQL" },
  "tables": [...],
  "createdAt": "..."
}

내부 흐름:
- Inspector로 Debezium config 생성 (dbType에 따라 분기)
- MetaDB에 pipeline 저장 (PENDING)
- provisioning 패키지 호출 (in-process)
- 즉시 202 반환
- 이후 status 변경은 WebSocket으로 push

검증:
- 같은 워크스페이스 내 name 중복 불가
- sourceDatabaseId의 cdcReadiness.status == GREEN 인지 확인
- tables가 비어있지 않은지
```

##### GET /api/pipelines
```json
응답 200:
[
  {
    "id": "...",
    "name": "orders-cdc",
    "type": "CDC",
    "status": "RUNNING",
    "sourceDatabase": {...},
    "tables": [...],
    "topicName": "cdc.table.acme.shop.public.orders",
    "createdAt": "..."
  }
]
```

##### GET /api/pipelines/{id}
```json
응답 200:
{
  "id": "...",
  "name": "orders-cdc",
  "type": "CDC",
  "status": "RUNNING",
  "statusMessage": null,
  "sourceDatabase": {...},
  "tables": [...],
  "topicName": "cdc.table.acme.shop.public.orders",
  
  "consumerConnectionInfo": {
    "bootstrapServers": "kafka-bootstrap.platform.svc:9094",
    "topic": "cdc.table.acme.shop.public.orders",
    "recommendedGroupId": "your-service-name",
    "security": {
      "protocol": "SASL_SSL",
      "mechanism": "SCRAM-SHA-512",
      "username": "proj-acme-user",
      "passwordSecretName": "proj-acme-user"
    },
    "codeSamples": {
      "java": "...",
      "python": "..."
    }
  },
  
  "createdAt": "...",
  "statusUpdatedAt": "..."
}

status 값:
- PENDING: 생성 요청됨, 아직 Strimzi가 작업 중
- RUNNING: Connector 정상 동작
- DEGRADED: Connector는 살아있지만 일부 Task 실패
- FAILED: Connector 시작 실패
- DELETING: 삭제 중
```

##### DELETE /api/pipelines/{id}
```json
응답 202:
{ "id": "...", "status": "DELETING" }
```

#### Discovered Services

##### GET /api/services
```json
응답 200:
[
  {
    "id": "...",
    "consumerGroupId": "payment-service",
    "subscribedTopics": ["cdc.table.acme.shop.public.orders"],
    "currentLag": 23,
    "status": "ACTIVE",
    "firstSeenAt": "...",
    "lastSeenAt": "..."
  }
]
```

##### PATCH /api/services/{id}
```json
요청 (사용자가 자동 발견된 Service에 라벨 붙임):
{
  "displayName": "결제 서비스",
  "description": "..."
}
응답 200: 업데이트된 객체
```

### 3.4 WebSocket 이벤트

```
WSS /ws/events?token=<jwt>
```

서버 → 클라이언트:
```json
{
  "type": "DATASOURCE_INSPECTED",
  "databaseId": "...",
  "cdcReadinessStatus": "GREEN" // or YELLOW, RED
}

{
  "type": "PIPELINE_STATUS_CHANGED",
  "pipelineId": "...",
  "oldStatus": "PENDING",
  "newStatus": "RUNNING"
}

{
  "type": "SERVICE_DISCOVERED",
  "service": { "id": "...", "consumerGroupId": "...", ... }
}

{
  "type": "SERVICE_LAG_UPDATED",
  "serviceId": "...",
  "currentLag": 1500
}
```

### 3.5 Inspector 모듈 인터페이스

core 내부 모듈이라 외부 API 없음. 내부 Java 인터페이스:

```java
public interface DatabaseInspector extends AutoCloseable {
    DbType getType();   // POSTGRESQL or MARIADB
    
    ConnectionTestResult testConnection();
    List<TableInfo> listTables();   // 모든 스키마의 모든 테이블 + 컬럼
    CDCReadinessReport checkCDCReadiness();
    DebeziumConnectorConfig generateConnectorConfig(PipelineSpec spec);
    
    @Override void close();
}

@Component
public class DatabaseInspectorFactory {
    public DatabaseInspector create(Database ds) {
        return switch (ds.getDbType()) {
            case POSTGRESQL -> new PostgresInspector(ds);
            case MARIADB -> new MariaDBInspector(ds);
        };
    }
}
```

CDC readiness 검사 항목:

**PostgreSQL**:
- wal_level = logical
- 사용자에게 REPLICATION 권한 있음
- max_replication_slots > 사용 중 슬롯 수
- max_wal_senders >= 1

**MariaDB**:
- log_bin = ON
- binlog_format = ROW
- binlog_row_image = FULL
- server_id > 0
- 사용자에게 REPLICATION SLAVE, REPLICATION CLIENT 권한 있음

### 3.6 MetaDB 스키마

```sql
CREATE TABLE workspaces (
    id UUID PRIMARY KEY,
    name VARCHAR(100) UNIQUE NOT NULL,
    namespace VARCHAR(63) UNIQUE NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE users (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES workspaces(id),
    email VARCHAR(255) UNIQUE NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE databases (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES workspaces(id),
    name VARCHAR(100) NOT NULL,
    db_type VARCHAR(20) NOT NULL,    -- POSTGRESQL | MARIADB
    host VARCHAR(255) NOT NULL,
    port INT NOT NULL,
    db_name VARCHAR(255) NOT NULL,
    username VARCHAR(255) NOT NULL,
    secret_ref VARCHAR(255) NOT NULL,
    cdc_readiness_status VARCHAR(20),
    cdc_readiness_report JSONB,
    last_inspected_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    UNIQUE (workspace_id, name)
);

CREATE TABLE pipelines (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES workspaces(id),
    name VARCHAR(100) NOT NULL,
    type VARCHAR(20) NOT NULL DEFAULT 'CDC',
    source_database_id UUID NOT NULL REFERENCES databases(id),
    tables JSONB NOT NULL,
    source_connector_name VARCHAR(255),
    topic_name VARCHAR(255),
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    status_message TEXT,
    status_updated_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    UNIQUE (workspace_id, name)
);

CREATE TABLE discovered_services (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES workspaces(id),
    consumer_group_id VARCHAR(255) NOT NULL,
    subscribed_topics TEXT[] NOT NULL,
    display_name VARCHAR(100),
    description TEXT,
    current_lag BIGINT,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    first_seen_at TIMESTAMP NOT NULL DEFAULT now(),
    last_seen_at TIMESTAMP NOT NULL DEFAULT now(),
    UNIQUE (workspace_id, consumer_group_id)
);

-- Sprint 4부터 사용
CREATE TABLE chat_sessions (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES workspaces(id),
    user_id UUID NOT NULL REFERENCES users(id),
    title VARCHAR(200),
    created_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE chat_messages (
    id UUID PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES chat_sessions(id),
    workspace_id UUID NOT NULL REFERENCES workspaces(id),
    role VARCHAR(20) NOT NULL,
    content TEXT NOT NULL,
    metadata JSONB,
    created_at TIMESTAMP NOT NULL DEFAULT now()
);
```

---

## 4. operations-backend

### 4.1 책임

- 워크스페이스 K8s 리소스 자동 생성
- Pipeline 배포 (KafkaConnector + KafkaTopic 생성)
- KafkaConnector status watch → Kafka 이벤트 발행
- Consumer Group 폴링 → Kafka 이벤트 발행
- K8s 리소스 정리

### 4.2 내부 REST API

> 인증: 서비스 간 공유 토큰 (`X-Internal-Token` 헤더)

##### POST /internal/workspaces/provision
```json
요청:
{
  "workspaceId": "uuid",
  "namespace": "proj-acme"
}

응답 200:
{
  "namespace": "proj-acme",
  "kafkaUser": "proj-acme-user",
  "status": "PROVISIONED"
}

작업:
1. Namespace 생성
2. KafkaUser CRD 생성 (SCRAM + ACL: proj-acme.* topics)
3. ResourceQuota 적용 (topics: 50, partitions: 200, etc.)
4. NetworkPolicy 적용 (egress to Kafka cluster only)
5. RoleBinding (KafkaUser secret 읽기 권한)
```

##### DELETE /internal/workspaces/{workspaceId}
```json
응답 202

작업:
- Namespace 삭제 (cascade)
- KafkaUser는 namespace 안에 있으니 함께 삭제
- KafkaUser용 Secret도 함께 삭제됨
```

##### POST /internal/pipelines
```json
요청:
{
  "pipelineId": "uuid",
  "workspaceId": "uuid",
  "namespace": "proj-acme",
  "name": "orders-cdc",
  "topicName": "cdc.table.acme.shop.public.orders",
  "topicPartitions": 3,
  "topicReplicationFactor": 3,
  "sourceDbSecretRef": "acme-ds-{databaseId}-creds",
  "debeziumConfig": {
    "className": "io.debezium.connector.postgresql.PostgresConnector",
    "properties": {
      "database.hostname": "...",
      "database.port": "5432",
      "database.user": "debezium",
      "database.password": "${secrets:acme-ds-xxx-creds/password}",
      "database.dbname": "orders",
      "plugin.name": "pgoutput",
      "slot.name": "debezium_pipeline_xxx",
      "publication.name": "dbz_pub_xxx",
      "table.include.list": "public.orders",
      "topic.prefix": "proj-acme.public.orders"
    }
  }
}

응답 202:
{
  "pipelineId": "uuid",
  "kafkaConnectorName": "orders-cdc",
  "status": "PENDING"
}

작업:
1. KafkaTopic CRD 생성 (Strimzi가 토픽 자동 생성)
2. KafkaConnector CRD 생성 (Strimzi가 Connect에 등록)
3. 즉시 202 반환
4. 실제 RUNNING은 watch가 감지해서 이벤트 발행
```

##### GET /internal/pipelines/{pipelineId}/status
```json
응답 200:
{
  "pipelineId": "uuid",
  "status": "RUNNING",
  "message": null,
  "tasks": [
    { "state": "RUNNING", "trace": null }
  ],
  "lastUpdatedAt": "..."
}

비고: K8s에서 KafkaConnector .status 직접 조회 → 캐시 없음
```

##### DELETE /internal/pipelines/{pipelineId}
```json
응답 202

작업:
- KafkaConnector CRD 삭제
- KafkaTopic CRD 삭제 (선택, 데이터 손실 주의)
```

### 4.3 Kafka 이벤트 (발행)

토픽: `platform.internal.connector-status`
```json
{
  "eventType": "CONNECTOR_STATUS_CHANGED",
  "pipelineId": "uuid",
  "workspaceId": "uuid",
  "kafkaConnectorName": "orders-cdc",
  "oldStatus": "PENDING",
  "newStatus": "RUNNING",
  "message": null,
  "timestamp": "..."
}
```

토픽: `platform.internal.service-discovered`
```json
{
  "eventType": "SERVICE_DISCOVERED",
  "workspaceId": "uuid",
  "consumerGroupId": "payment-service",
  "subscribedTopics": ["cdc.table.acme.shop.public.orders"],
  "timestamp": "..."
}

{
  "eventType": "SERVICE_LAG_UPDATED",
  "workspaceId": "uuid",
  "consumerGroupId": "payment-service",
  "currentLag": 1500,
  "timestamp": "..."
}

{
  "eventType": "SERVICE_DISCONNECTED",
  "workspaceId": "uuid",
  "consumerGroupId": "payment-service",
  "timestamp": "..."
}
```

### 4.4 백그라운드 작업

#### Connector status watcher
- 모든 namespace의 KafkaConnector를 K8s watch API로 구독
- status 변경 시 Kafka 이벤트 발행
- (실제로는 Strimzi가 status 필드 채우는 걸 우리가 감지)

#### Consumer group discoverer
- 30초마다 Kafka Admin API로 모든 consumer group 조회
- workspace prefix(`proj-*-user`)로 워크스페이스 식별
- 새 그룹 → SERVICE_DISCOVERED 이벤트
- lag 변화 → SERVICE_LAG_UPDATED 이벤트
- 5분 이상 활동 없음 → SERVICE_DISCONNECTED 이벤트

---

## 5. ai-service (Sprint 4부터)

MVP의 핵심 경로(대시보드)에서는 안 씀. Sprint 4에 추가.

### 5.1 책임

- core에서 채팅 메시지 + 컨텍스트 받음
- OpenAI 호출 (도구 정의 포함)
- 도구 호출 결정 시 → core API로 위임
- SSE로 응답 스트리밍

### 5.2 도구 정의 (MVP)

| 도구 | 호출하는 core API |
| --- | --- |
| `list_databases` | GET /api/databases |
| `get_database_tables` | GET /api/databases/{id}/tables |
| `inspect_database_readiness` | GET /api/databases/{id} (cdcReadiness 필드) |
| `create_cdc_pipeline` | POST /api/pipelines |
| `list_pipelines` | GET /api/pipelines |
| `get_pipeline_detail` | GET /api/pipelines/{id} |

### 5.3 내부 API

##### POST /internal/ai/chat (SSE)
```json
요청:
{
  "sessionId": "uuid",
  "workspaceContext": {
    "workspaceId": "...",
    "userId": "...",
    "userToken": "..."   // 도구 호출에 사용
  },
  "messages": [
    { "role": "USER", "content": "..." },
    { "role": "ASSISTANT", "content": "..." }
  ]
}

응답: SSE stream (위 1번 시연 시나리오의 채팅 스트림과 동일)
```

---

## 6. Frontend (대시보드 중심)

### 6.1 화면 구조

#### S1. 로그인 / 회원가입
- 이메일, 비밀번호
- 회원가입 시 워크스페이스 이름 추가
- 회원가입 후 자동 로그인

#### S2. 메인 (캔버스)
- React Flow 캔버스가 중심
- 노드 타입:
  - Database (왼쪽)
  - Pipeline (가운데, 엣지 형태로도 표현 가능)
  - Service (오른쪽)
- 상단 툴바: [+ Database 등록] [+ Pipeline 생성]
- 노드 클릭 → 우측 상세 패널
- (Sprint 4) 좌측 채팅 패널 토글

#### S3. Database 등록 위저드
- Step 1: 기본 정보 (이름, **dbType 선택 (PG/MariaDB)**)
- Step 2: 연결 정보 (host, port, dbName, username, password)
- Step 3: 등록 → CDC readiness 자동 검사 → 결과 표시
- 검사 결과 RED인 경우 항목별 remediation 안내

#### S4. Pipeline 생성 위저드
- Step 1: 이름 입력
- Step 2: Source Database 선택 (드롭다운, CDC readiness = GREEN인 것만)
- Step 3: 테이블 선택 (체크박스 트리, 스키마 → 테이블)
- Step 4: 생성 → 캔버스로 돌아감

#### S5. Pipeline 상세 패널
- 이름, 상태, 토픽
- Consumer 연결 정보 (탭으로 Java/Python 코드)
- "복사" 버튼
- "삭제" 버튼

#### S6. Database 상세 패널
- CDC readiness 상세 (체크 항목별)
- 테이블 목록 미리보기
- "재검사" 버튼
- "삭제" 버튼

### 6.2 컴포넌트 구조

```
src/
├─ pages/
│   ├─ LoginPage.tsx
│   ├─ RegisterPage.tsx
│   └─ MainPage.tsx
├─ components/
│   ├─ canvas/
│   │   ├─ Canvas.tsx
│   │   ├─ DatabaseNode.tsx
│   │   ├─ PipelineNode.tsx       (또는 PipelineEdge)
│   │   └─ ServiceNode.tsx
│   ├─ wizards/
│   │   ├─ DatabaseWizard.tsx
│   │   └─ PipelineWizard.tsx
│   ├─ panels/
│   │   ├─ DatabaseDetailPanel.tsx
│   │   ├─ PipelineDetailPanel.tsx
│   │   └─ ServiceDetailPanel.tsx
│   └─ chat/                      ← Sprint 4
│       └─ ChatPanel.tsx
├─ api/
│   ├─ client.ts
│   ├─ auth.ts
│   ├─ databases.ts
│   ├─ pipelines.ts
│   └─ services.ts
├─ hooks/
│   ├─ useWebSocket.ts
│   ├─ useCanvasData.ts
│   └─ useDatabases.ts
└─ store/
    └─ canvasStore.ts
```

---

## 7. 통합 시나리오 (대시보드 모드)

> 아래 흐름의 `core`/`orchestrator` 단계는 모놀리스 통합 전 표기다([§2 노트](#2-서비스-구성), [ADR 0004](../adr/0004-monorepo-monolith.md)). 현재는 모두 **`operations-backend` 한 프로세스 안의 패키지 호출**이며, `core → orchestrator: POST /internal/pipelines`나 `orchestrator → Kafka 이벤트 → core 구독`은 **provisioning 호출 + watcher → `PipelineStatusService`(in-process)** 로 대체된다.

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

## 8. Sprint 일정 (재정리)

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

## 9. 완료 정의 (DoD)

MVP가 끝났다고 말할 수 있는 기준:

- [ ] 신규 사용자가 회원가입 → 5분 내 첫 CDC 파이프라인 생성 가능
- [ ] **PostgreSQL과 MariaDB 둘 다** Database 등록 + CDC 동작
- [ ] 사용자 DB의 INSERT/UPDATE/DELETE가 Kafka 토픽에 도착
- [ ] 컨슈머 연결 시 캔버스에 Service 노드 자동 등장
- [ ] 2개 이상의 워크스페이스 동시 사용해도 데이터/리소스 격리 (한 워크스페이스가 다른 토픽 못 봄)
- [ ] Pipeline 삭제 시 K8s 리소스 + 토픽 정리
- [ ] (Stretch) 채팅으로 같은 흐름 가능

---

## 10. 결정해야 할 항목 (Sprint 0)

이거 다음 회의에 정해야 함:

1. **공통 DTO 모듈**: `common-dto` Gradle 모듈로 분리 vs 각 서비스 자체 정의
2. **에러 코드 체계**: HTTP 상태 + 비즈니스 코드 형식
3. **서비스 간 인증**: 단순 공유 토큰 vs mTLS
4. **로깅 포맷**: JSON, 필수 필드 (traceId, workspaceId, userId)
5. **API 문서**: SpringDoc OpenAPI 활성화 (자동 TS client 생성용)
6. **로컬 개발 환경**: Docker Compose 구성
7. **테스트 DB**: Testcontainers 표준화

---

이 명세는 살아있는 문서. Sprint 진행하며 변경되는 부분 반영.
