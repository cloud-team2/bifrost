# Spring Boot Operations Backend — Provisioning

> 요약은 [overview.md](./overview.md). 이 파일은 파이프라인과 Kafka 리소스 생성·관리(프로비저닝)를 다룬다.

## 2. Provisioning

### 1. 목적

이 문서는 Spring Boot Operations Backend가 **파이프라인과 Kafka 리소스를 생성·관리**하는 방법을 정의한다. 장애 대응(에이전트)이 아니라 **플랫폼 본체** 기능이다.

원천 설계는 구현방법 문서(Strimzi Operator · Fabric8 · Debezium · JDBC Sink)를 따르되, 실제 클러스터 상태와 인프라 제약에 맞춰 다음을 기준으로 한다(infra 문서 우선).

| 항목 | 기준 | 비고 |
| --- | --- | --- |
| Kafka 모드 | **KRaft** (ZooKeeper 없음) | Kafka 4.x, [Infra DETAILS](../infra.md). 구현방법의 ZooKeeper 언급은 폐기 |
| 클러스터/네임스페이스 | Kafka CR `platform-kafka`, ns `platform-kafka` | bootstrap `platform-kafka-kafka-bootstrap:9094` (SCRAM-SHA-512, TLS) |
| KafkaConnect | `platform-connect` (replicas 2) | |
| 플러그인 이미지 레지스트리 | **Harbor** | infra: ECR 사용 불가. 구현방법의 ECR push는 Harbor로 대체 |
| K8s 접근 | Spring Boot의 Fabric8 Kubernetes Client | Agent는 직접 접근 금지 |

### 1.1 Provisioner 추상화 (인터페이스)

파이프라인 생성 흐름은 실제 Kafka/K8s 구현과 **인터페이스로 분리**한다. API 계약(command/result)이 고정되어 호출부는 구현을 모른다.

```java
public interface KafkaPipelineProvisioner {
    PipelineProvisionResult createPipelineResources(PipelineProvisionCommand command);
    PipelineProvisionStatus getConnectorStatus(String projectId, String connectorName);
    void deletePipelineResources(PipelineResourceRef resourceRef);
}
```

- **구현체**: `StrimziKafkaPipelineProvisioner` 단일 구현(아래 §3~§6, Fabric8/Strimzi CR 생성·watch). 파이프라인 생성은 항상 Strimzi(K8s)를 거치므로 로컬에서도 kind+Strimzi가 필요하다([local-dev-fullstack.md](../../guides/local-dev-fullstack.md) 트랙 B).
- **부분 실패**는 result로 구분한다: Secret/Topic/Connector 중 어느 단계에서 실패했는지, Connector는 생성됐으나 state가 FAILED인지 등을 식별해 pipeline `error`로 반영.

> 토픽 네이밍 규칙: **`cdc.table.{projectKey}.{dbName}.{schema}.{table}`** (table 중심). Debezium `topic.prefix = cdc.table.{projectKey}.{dbName}` → `.{schema}.{table}` 자동 부여. KafkaUser ACL은 프로젝트 격리를 위해 `cdc.table.{projectKey}.*` prefix로 부여한다.

### 2. 전체 흐름

```text
① 인프라 부트스트랩 (최초 1회)
   Kafka(KRaft) · KafkaConnect(build) · workspace별 KafkaUser

② 파이프라인 생성 시 (Spring Boot → Fabric8 → K8s API)
   KafkaConnector CR apply (Source = Debezium)
     → Debezium이 기동하며 토픽 자동 생성:
       cdc.table.{projectKey}.{dbName}.{schema}.{table}
   KafkaConnector CR apply (Sink = JDBC, CDC만)

③ Strimzi Operator가 CR을 watch하여 실제 리소스 생성·관리
   Connector state 변화 → Fabric8 watch → pipeline 테이블 갱신 → SSE push
```

원칙: **파이프라인 1개 = 단일 테이블 1개**. `table.include.list`는 항상 단일 테이블. KafkaTopic CR을 따로 만들지 않고 Debezium이 토픽을 자동 생성한다.

### 3. 인프라 부트스트랩 (최초 1회)

#### 3.1 KafkaConnect — 플러그인 이미지

KafkaConnect CR의 `spec.build`로 플러그인을 포함한 이미지를 빌드하고 Harbor에 push한다.

| 플러그인 | 역할 |
| --- | --- |
| Debezium PostgreSQL Source | PostgreSQL WAL → Kafka Topic |
| Debezium MariaDB Source | MariaDB Binlog → Kafka Topic |
| Confluent JDBC Sink | Kafka Topic → PostgreSQL / MariaDB |
| PostgreSQL JDBC Driver | JDBC Sink 쓰기용 |
| MariaDB JDBC Driver | JDBC Sink 쓰기용 |

`config/offset/status.storage.replication.factor = 3`, Connect REST는 cluster internal(ClusterIP)로만 노출. Agent는 Connect REST를 직접 호출하지 않고 Spring Boot가 호출한다.

#### 3.2 KafkaUser — 워크스페이스 단위 (FR-002)

워크스페이스 생성 시 `KafkaUser` CR을 apply한다. ACL은 해당 워크스페이스 prefix 토픽 전체에 read/write를 허용한다. Strimzi가 동명 Secret(SCRAM 자격증명)을 자동 생성하며, 그 워크스페이스의 모든 Connector CR이 이 Secret을 참조한다.

```text
project A 생성
  → KafkaUser CR: proj-{projectKey}-user
      ACL: topic prefix "cdc.table.{projectKey}.*" read/write
  → Strimzi가 동명 Secret 자동 생성 (Kafka SASL 자격증명)
  → 이후 project A의 모든 KafkaConnector → 이 Secret 참조
```

KafkaUser 단위 = 프로젝트(워크스페이스). 파이프라인을 추가해도 재생성하지 않는다. 프로젝트 간 토픽 접근은 `cdc.table.{projectKey}.*` ACL로 격리된다.

> **자격증명 구분**: KafkaUser Secret은 **Kafka 접속용(SASL/SCRAM)** 이다. source/sink **DB 자격증명**은 별개로 **secretRef(K8s Secret/Secrets Manager)** 로 보관하고, Connector 생성 시 Secret을 참조(주입)한다. DB에는 자격증명 평문/암호문을 저장하지 않는다([§3 Database Registry](./database-registry.md#3-database-registry)).

> **Connect↔Kafka 인증**: KafkaConnect 클러스터는 `scram` listener(`...:9094`, SCRAM-SHA-512, TLS)로 Kafka에 접속한다. plain 9092는 운영 기준 비표준이므로 사용하지 않는다([Infra DETAILS](../infra.md) §4.2). 워크스페이스별 토픽 격리는 KafkaConnect 클러스터 단일 ID로는 강제되지 않으므로, KafkaConnector CR의 `producer.override.sasl.*`/`consumer.override.sasl.*`에 해당 워크스페이스 KafkaUser(`proj-{projectKey}-user`)의 SCRAM 자격증명을 주입해 ACL(`cdc.table.{projectKey}.*`)이 실제로 적용되게 한다.

### 4. 파이프라인 생성 (FR-004)

마법사 완료 시 Spring Boot가 Fabric8로 CR을 순서대로 apply한다.

| 패턴 | KafkaConnector Source | KafkaConnector Sink | 자동 생성 토픽 |
| --- | --- | --- | --- |
| **EDA (fan-out)** | 1개 (Debezium) | 없음 | 1개 |
| **CDC (direct)** | 1개 (Debezium) | 1개 (JDBC Sink) | 1개 |

#### 4.1 Source Connector

- PostgreSQL → `io.debezium.connector.postgresql.PostgresConnector` (plugin.name=pgoutput), MariaDB → `io.debezium.connector.mariadb.MariaDbConnector`.
- `tasksMax=1` 고정 (WAL/Binlog 순서 보장).
- `table.include.list` = 단일 테이블.
- `topic.prefix = cdc.table.{projectKey}.{dbName}` → 토픽 `...{schema}.{table}` 자동 생성, `topic.creation.default.partitions=6`, `replication.factor=3`.
- `database.password`는 secretRef가 가리키는 K8s Secret/Secrets Manager에서 **생성 시점에만 resolve**해 주입한다(평문·암호문을 메타DB에 두지 않음). 메타DB의 `database` 행에는 `secret_ref`만 있다([§3 Database Registry](./database-registry.md#3-database-registry)). 더 안전한 방식으로는 평문 주입 대신 KafkaConnector config에 `${secrets:...}` 형태의 Strimzi `KafkaConnect.spec.config.providers`(FileConfigProvider/DirectoryConfigProvider) 참조를 쓸 수 있다.

```java
// 생성 시점에만 SecretStore에서 자격증명 해석 (메타DB엔 secret_ref만 저장)
DbCredential cred = secretStore.resolve(sourceDb.getSecretRef());

KafkaConnector source = new KafkaConnectorBuilder()
    .withNewMetadata()
        .withName(pipelineId + "-source")
        .withNamespace("platform-kafka")
        .addToLabels("strimzi.io/cluster", "platform-connect")
    .endMetadata()
    .withNewSpec()
        .withClassName("io.debezium.connector.postgresql.PostgresConnector")
        .withTasksMax(1)
        .addToConfig("database.hostname", sourceDb.getHost())
        .addToConfig("database.dbname",   sourceDb.getName())
        .addToConfig("database.user",     cred.user())
        .addToConfig("database.password", cred.password())   // resolve 결과, State·로그에 남기지 않음
        .addToConfig("topic.prefix",      "cdc.table." + projectKey + "." + dbName)
        .addToConfig("plugin.name",       "pgoutput")
        // JDBC sink가 키(PK Struct)·값 타입을 읽을 수 있도록 스키마 인지 JSON을 강제(worker 기본값 false 오버라이드)
        .addToConfig("key.converter",                "org.apache.kafka.connect.json.JsonConverter")
        .addToConfig("key.converter.schemas.enable", "true")
        .addToConfig("value.converter",                "org.apache.kafka.connect.json.JsonConverter")
        .addToConfig("value.converter.schemas.enable", "true")
        // 시간 타입을 Connect 논리 타입으로(기본 adaptive는 epoch 마이크로초 int64 → sink 컬럼이 BIGINT가 됨)
        .addToConfig("time.precision.mode", "connect")
    .endSpec()
    .build();
kubernetesClient.resource(source).inNamespace("platform-kafka").create();
```

> 여기서 `projectKey`는 실제 `workspace.namespace` 컬럼을 API/documentation에서 부르는 슬러그 이름이다. `crypto.decrypt(encPassword)` 같은 메타DB 암호문 저장 방식은 폐기한다 — 자격증명은 SecretStore에만 둔다.

#### 4.2 Sink Connector (CDC만)

- `io.confluent.connect.jdbc.JdbcSinkConnector`, `tasksMax=3` (파티션 병렬).
- `topics` = Debezium이 만든 토픽명.
- `insert.mode=upsert`, `pk.mode=record_key` (중복 없는 적재).
- 컨버터는 source와 동일하게 **스키마 인지 JSON**(`key/value.converter.schemas.enable=true`) — `pk.mode=record_key`가 스키마 없는 HashMap 키를 거부하기 때문.
- SMT 체인 `unwrap,route`:
  - `unwrap` = `io.debezium.transforms.ExtractNewRecordState` (envelope 평탄화, after-image만 적재).
  - `route` = `RegexRouter`(`.*\.([^.]+)$` → `$1`)로 토픽명 `cdc.table.{project}.{db}.{schema}.{table}`을 마지막 세그먼트(테이블명)로 축약. JDBC sink는 토픽명을 테이블 식별자로 쓰는데 점(.)이 있으면 MariaDB/MySQL이 `catalog.table`로 오해하므로 필수.

### 5. 생명주기 (FR-005)

> 삭제 정책·실패 attribution의 정본은 [lifecycle.md](./lifecycle.md). 아래는 프로비저닝 관점 요약.

| 동작 | 구현 |
| --- | --- |
| pause | KafkaConnector `state: paused` patch |
| resume | `state: running` patch |
| delete | KafkaConnector CR 삭제(이름 기반 + pid 접두사 sweep) → **토픽·sink consumer group 정리** → pipeline 행 제거 |

- **삭제 보장(#155)**: CR 정리는 반드시 성공해야 하며, 실패 시 예외가 트랜잭션을 롤백시켜 행이 남는다(다음 시도 재정리) → **고아 KafkaConnector CR이 절대 남지 않는다**. `force=true`는 `creating` 상태 가드만 우회한다.
- **Kafka 잔재 정리(#200, best-effort)**: CR을 지워도 Kafka Connect는 sink consumer group을, Debezium은 토픽을 자동 삭제하지 않는다. `KafkaResourceCleaner`가 **consumer group이 빌 때까지 기다렸다 먼저 지우고 토픽을 맨 마지막**에 지운다(순서 반대면 `GroupNotEmptyException`·`auto.create`로 인한 빈 토픽 재생성). 남기면 orphan group lag 합산·재스냅샷 누적 문제가 생긴다([lifecycle.md §6](./lifecycle.md)).
- `creating`은 Connector가 RUNNING으로 전이할 때까지 유지하고, 타임아웃 초과 시 `error`로 내려 상태 정확성·삭제 가능성을 보장한다(`ProvisioningTimeoutJob`).

> **아직 정리하지 않는 잔재**: PostgreSQL source의 publication·replication slot(`bif_{project}_{pid}_*`)은 삭제 시 정리 대상에 미포함(후속).

### 6. Connector 상태 감지 — Fabric8 Watch (FR-008)

`KafkaConnector` CR의 `.status.connectorStatus.connector.state`(RUNNING / FAILED / PAUSED / UNASSIGNED)와 task별 상태가 바뀔 때마다 pipeline 테이블을 갱신하고 SSE(`connector_state_changed`, `pipeline_status_changed`)로 push한다. 일부 task만 FAILED면 connector를 `PARTIALLY_FAILED`(Bifrost 합성 상태)로 보고 pipeline은 `lag`으로 둔다([부록 B.2](../../spec.md#b2-connector-인스턴스-상태값)).

```java
kubernetesClient.resources(KafkaConnector.class)
    .inNamespace("platform-kafka")
    .withLabel("strimzi.io/cluster", "platform-connect")
    .watch(new Watcher<KafkaConnector>() {
        public void eventReceived(Action action, KafkaConnector r) {
            String state = readConnectorState(r);   // .status.connectorStatus.connector.state
            pipelineService.updateConnectorStatus(r.getMetadata().getName(), state);
            // → pipeline status 재계산 → SSE push
        }
        public void onClose(WatcherException e) { /* 재구독 */ }
    });
```

상태→파이프라인 매핑과 임계값(consumer lag 5,000/50,000, error rate 0.5%/2% 등)은 [기능명세서 부록 B](../../spec.md#부록-b--리소스-상태값-정의-및-자동-기준-단일-출처)를 단일 출처로 따른다. `creating`은 RUNNING 전이까지(최대 30초) 유지하고, lag 임계 초과는 watch가 아니라 consumer lag metric으로 판정한다(폴링 수집기 기반, MVP 미구현 가능 — [server.md §11.1](./server.md#111-관측모니터링-데이터-수집-상태-vs-지표)).

### 7. 보안·정책

- 모든 K8s/Connect 호출은 Spring Boot의 제한된 ServiceAccount로만 수행(Agent는 credential 없음).
- 토픽 delete, 임의 manifest apply, pod exec는 제공하지 않는다.
- connector config 변경은 변경관리(change management) 대상.
- 프로비저닝 동작도 audit event로 남긴다([§1 Server Design §10](./server.md#1-server-design)).
