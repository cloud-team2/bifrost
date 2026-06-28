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

- **구현체**: `StrimziKafkaPipelineProvisioner` 단일 구현(아래 §3~§6, Fabric8/Strimzi CR 생성·watch). 파이프라인 생성은 항상 Strimzi(K8s)를 거치므로 로컬에서도 kind+Strimzi가 필요하다.
- **부분 실패**는 result로 구분한다: 현재 stage enum은 `SECRET`, `SOURCE_CONNECTOR`, `SINK_CONNECTOR`, `COMPLETED`다. 별도 `TOPIC` stage는 없다.

> 토픽 네이밍 규칙: **`{root}.{projectKey}.{dbSlug}.{schema}.{table}`** (table 중심, `root=cdc.table|eda.table`). `dbSlug = {datasource.dbName}-{datasourceId 앞 8 hex}` — `name` 표시값이 아니라 물리 DB 이름(`db_name`)과 datasource 고유 id를 섞어 충돌을 막는다. 현재 mapper는 Debezium `topic.prefix`를 최종 토픽명으로 두고 route SMT로 Debezium의 `.{schema}.{table}` 중복 suffix를 제거한다(#365). KafkaUser ACL section은 현재 `TenantProvisioner`가 CR에 포함하지 않는다.

### 2. 전체 흐름

```text
① 인프라 부트스트랩 (최초 1회)
   Kafka(KRaft) · KafkaConnect(build) · workspace별 KafkaUser

② 파이프라인 생성 시 (Spring Boot → Fabric8 → K8s API)
   KafkaConnector CR apply (Source = Debezium)
     → Kafka Connect/Debezium topic creation으로 토픽 자동 생성:
       {root}.{projectKey}.{dbSlug}.{schema}.{table}
   KafkaConnector CR apply (Sink = JDBC, CDC만)

③ Strimzi Operator가 CR을 watch하여 실제 리소스 생성·관리
   Connector state 변화 → Fabric8 watch → pipeline 테이블 갱신 → SSE push
```

원칙: **파이프라인 1개 = 단일 테이블 1개**. `table.include.list`는 항상 단일 테이블. KafkaTopic CR을 따로 만들지 않고 Kafka Connect/Debezium이 토픽을 자동 생성한다.

> 정책(#743): 파이프라인 데이터 토픽은 Strimzi Topic Operator 관리 대상이 아니다. 따라서 라이브 브로커나 앱 Kafka metadata에는 토픽이 보이지만 `kubectl get kafkatopic`에는 해당 토픽 CR이 없는 상태가 정상이다. `KafkaTopic` CR은 현재 `platform-internal-*` 이름의 플랫폼 내부 CR처럼 명시 관리가 필요한 토픽에만 사용한다.

### 3. 인프라 부트스트랩 (최초 1회)

#### 3.1 KafkaConnect — 플러그인 이미지

KafkaConnect CR의 `spec.image`로 커스텀 Connect 이미지를 참조한다. 이미지는
`infra/docker/kafka-connect/Dockerfile`(멀티스테이지)로 빌드해 Harbor에 push한다(CI: `Jenkinsfile`의 `Build Kafka Connect image` 스테이지).

> 과거에는 `spec.build`(in-cluster Kaniko)로 빌드했으나, plugin artifact를 URL로만 받을 수 있어
> private repo의 timestamptz 커스텀 컨버터 JAR을 넣을 수 없었다(#425). 이미지를 직접 빌드하는
> `spec.image`로 일원화했다.

| 플러그인 | 역할 |
| --- | --- |
| Debezium PostgreSQL Source | PostgreSQL WAL → Kafka Topic |
| Debezium MariaDB Source | MariaDB Binlog → Kafka Topic |
| Confluent JDBC Sink | Kafka Topic → PostgreSQL / MariaDB |
| PostgreSQL JDBC Driver | JDBC Sink 쓰기용 |
| MariaDB JDBC Driver | JDBC Sink 쓰기용 |
| timestamptz 커스텀 컨버터 (#425) | Postgres `timestamptz` → Connect Timestamp (sink 타입 불일치 방지) |

`config/offset/status.storage.replication.factor = 3`, Connect REST는 cluster internal(ClusterIP)로만 노출. Agent는 Connect REST를 직접 호출하지 않고 Spring Boot가 호출한다.

#### 3.2 KafkaUser — 워크스페이스 단위 (FR-002)

워크스페이스 생성 시 `KafkaUser` CR을 apply한다. 현재 `TenantProvisioner`는 `authentication: scram-sha-512`만 넣고 authorization/ACL section은 제외한다. Strimzi가 동명 Secret(SCRAM 자격증명)을 자동 생성하지만, 현재 connector mapper는 workspace KafkaUser Secret을 connector traffic에 주입하지 않는다.

```text
project A 생성
  → KafkaUser CR: proj-{projectKey}-user
      authentication: scram-sha-512
      authorization/ACL: 현재 CR에 포함하지 않음
  → Strimzi가 동명 Secret 자동 생성 (Kafka SASL 자격증명)
  → 현재 KafkaConnector mapper는 이 Secret을 producer/consumer override에 참조하지 않음
```

KafkaUser 단위 = 프로젝트(워크스페이스). 파이프라인을 추가해도 재생성하지 않는다. 프로젝트 간 토픽 접근 ACL 격리는 목표 구조지만 현재 KafkaUser CR에는 authorization section이 없다.

> **자격증명 구분**: KafkaUser Secret은 **Kafka 접속용(SASL/SCRAM)** 이다. source/sink **DB 자격증명**은 별개로 `secretRef`를 통해 `SecretStore`에서 해석한다. 현재 구현의 `DbSecretStore`는 metadb `secrets.credential_json`을 사용하며, API 응답과 datasource row에는 secret material을 노출하지 않는다([§3 Database Registry](./database-registry.md#3-database-registry)).

> **Connect↔Kafka 인증**: 문서의 목표 구조는 workspace KafkaUser ACL을 connector traffic에 적용하는 것이다. 현재 mapper config에는 `producer.override.sasl.*`/`consumer.override.sasl.*` 주입이 없고, MariaDB schema history bootstrap 기본값도 plain `localhost:9092`다. 따라서 per-workspace KafkaUser SCRAM override가 현재 connector CR에 반영된 것으로 문서화하면 안 된다.

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
- `topic.prefix = {root}.{projectKey}.{dbSlug}.{schema}.{table}`(`root=cdc.table|eda.table`, `dbSlug = {dbName}-{datasourceId 앞 8 hex}`, #265/#365) → route SMT로 Debezium의 중복 `.{schema}.{table}` suffix를 제거해 최종 토픽명을 유지한다. 토픽은 `topic.creation.default.partitions=6`, `replication.factor=3` 설정으로 Kafka Connect/Debezium이 자동 생성하며 KafkaTopic CR은 만들지 않는다.
- `database.password`는 `secretRef`가 가리키는 `SecretStore`에서 **생성 시점에 resolve**해 주입한다. 현재 `DbSecretStore` provider는 metadb `secrets.credential_json`에 저장된 `{"user":"...","password":"..."}`를 resolve하며, `datasource` 행에는 `secret_ref`만 있다([§3 Database Registry](./database-registry.md#3-database-registry)). API/log에는 secret material을 노출하지 않는다.

```java
// 생성 시점에만 SecretStore에서 자격증명 해석
DbCredential cred = secretStore.resolve(sourceDb.getSecretRef());
String topicRoot = pattern == PipelinePattern.FAN_OUT ? "eda.table" : "cdc.table";
String topicName = topicRoot + "." + projectKey + "." + dbSlug + "." + schema + "." + table;

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
        .addToConfig("topic.prefix",      topicName)
        .addToConfig("table.include.list", schema + "." + table)
        .addToConfig("transforms", "route")
        .addToConfig("transforms.route.type", "org.apache.kafka.connect.transforms.RegexRouter")
        .addToConfig("transforms.route.regex", "(.*)\\." + schema + "\\." + table + "$")
        .addToConfig("transforms.route.replacement", "$1")
        .addToConfig("plugin.name",       "pgoutput")
        // JDBC sink가 키(PK Struct)·값 타입을 읽을 수 있도록 스키마 인지 JSON을 강제(worker 기본값 false 오버라이드)
        .addToConfig("key.converter",                "org.apache.kafka.connect.json.JsonConverter")
        .addToConfig("key.converter.schemas.enable", "true")
        .addToConfig("value.converter",                "org.apache.kafka.connect.json.JsonConverter")
        .addToConfig("value.converter.schemas.enable", "true")
        // 시간 타입을 Connect 논리 타입으로(기본 adaptive는 epoch 마이크로초 int64 → sink 컬럼이 BIGINT가 됨)
        .addToConfig("time.precision.mode", "connect")
        // (#425) timestamptz는 time.precision.mode와 무관하게 Debezium이 ZonedTimestamp(문자열)로 방출 →
        // JDBC sink가 varchar로 적재 → 타입 불일치. 커스텀 컨버터로 Connect Timestamp로 변환한다(Postgres 전용).
        // Debezium 규약: 타입 키는 `<alias>.type` (converters.<alias>.type 아님, #462)
        .addToConfig("converters", "timestamptz")
        .addToConfig("timestamptz.type", "com.bifrost.connect.converter.TimestamptzConverter")
    .endSpec()
    .build();
kubernetesClient.resource(source).inNamespace("platform-kafka").create();
```

> 여기서 `projectKey`는 실제 `workspace.namespace` 컬럼을 API/documentation에서 부르는 슬러그 이름이다. `crypto.decrypt(encPassword)` 같은 datasource row 암호문 저장 방식은 폐기한다. 현재 provider는 `SecretStore` 구현으로 metadb `secrets.credential_json`을 사용한다.

#### 4.2 Sink Connector (CDC만)

- `io.confluent.connect.jdbc.JdbcSinkConnector`, `tasksMax=3` (파티션 병렬).
- `topics` = Debezium이 만든 토픽명.
- `insert.mode=upsert`, `pk.mode=record_key` (중복 없는 적재).
- 컨버터는 source와 동일하게 **스키마 인지 JSON**(`key/value.converter.schemas.enable=true`) — `pk.mode=record_key`가 스키마 없는 HashMap 키를 거부하기 때문.
- SMT 체인 `unwrap,route`:
  - `unwrap` = `io.debezium.transforms.ExtractNewRecordState` (envelope 평탄화, after-image만 적재).
  - `route` = `RegexRouter`(`.*\.([^.]+)$` → `$1`)로 source 토픽명(`{root}.{projectKey}.{dbSlug}.{schema}.{table}`)을 마지막 세그먼트(테이블명)로 축약. JDBC sink는 토픽명을 테이블 식별자로 쓰는데 점(.)이 있으면 MariaDB/MySQL이 `catalog.table`로 오해하므로 필수.

### 5. 생명주기 (FR-005)

> 삭제 정책·실패 attribution의 정본은 [lifecycle.md](./lifecycle.md). 아래는 프로비저닝 관점 요약.

| 동작 | 구현 |
| --- | --- |
| pause | 현재 platform `PipelineService.pause(...)`는 metadb pipeline status만 `paused`로 변경한다. KafkaConnector state patch는 아직 연결되어 있지 않다. |
| resume | 현재 platform `PipelineService.resume(...)`는 metadb pipeline status를 `active`로 변경한다. |
| delete | KafkaConnector CR 삭제(이름 기반 + pid 접두사 sweep) → **토픽·sink consumer group 정리** → pipeline 행 제거 |

- **삭제 보장(#155)**: 결정적 이름의 Source/Sink CR 삭제는 `deletePipelineResources(...)`에서 수행한다. pid 접두사 sweep은 보강 경로이며, 목록 조회 실패는 warn log만 남기고 행 삭제가 계속될 수 있다. 따라서 "고아 KafkaConnector CR 0건"을 트랜잭션으로 보장하지는 않는다. `force=true`는 `creating` 상태 가드만 우회한다.
- **Kafka 잔재 정리(#200, best-effort)**: CR을 지워도 Kafka Connect는 sink consumer group을, Debezium은 토픽을 자동 삭제하지 않는다. `KafkaResourceCleaner`가 **consumer group이 빌 때까지 기다렸다 먼저 지우고 토픽을 맨 마지막**에 지운다(순서 반대면 `GroupNotEmptyException` 또는 자동 topic creation 환경의 빈 토픽 재생성 위험이 있다). 남기면 orphan group lag 합산·재스냅샷 누적 문제가 생긴다([lifecycle.md §6](./lifecycle.md)).
- `creating`은 Connector가 RUNNING으로 전이할 때까지 유지하고, 타임아웃 초과 시 `error`로 내려 상태 정확성·삭제 가능성을 보장한다(`ProvisioningTimeoutJob`).

> **아직 정리하지 않는 잔재**: PostgreSQL source의 publication·replication slot(`bif_{project}_{pid}_*`)은 삭제 시 정리 대상에 미포함(후속).

### 6. Connector 상태 감지 — Fabric8 Watch (FR-008)

`KafkaConnector` CR의 `.status.connectorStatus.connector.state`(RUNNING / FAILED / PAUSED / UNASSIGNED)와 task별 상태가 바뀔 때마다 pipeline 테이블을 갱신하고 SSE(`connector_state_changed`, `pipeline_status_changed`)로 push한다. 일부 task만 FAILED면 connector를 `PARTIALLY_FAILED`(Bifrost 합성 상태)로 보고 pipeline은 `error`로 둔다([부록 B.2](../../spec.md#b2-connector-인스턴스-상태값)).

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

현재 pipeline 상태 전이는 `PipelineStatusServiceImpl`이 connector state와 DB reachability로 계산한다. `creating` timeout은 `error`로 전이한다. consumer lag/error-rate 임계값은 기능명세서 부록 B의 목표 신호지만 현재 `recompute` 입력에는 들어가지 않는다.

### 7. 보안·정책

- 모든 K8s/Connect 호출은 Spring Boot의 제한된 ServiceAccount로만 수행(Agent는 credential 없음).
- 토픽 delete, 임의 manifest apply, pod exec는 제공하지 않는다.
- connector config 변경은 변경관리(change management) 대상.
- 프로비저닝 동작도 Spring `AuditService` 경로로 audit event를 남긴다.
