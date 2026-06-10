# Kafka Connect 커스텀 이미지 — 빌드와 롤아웃 (#425)

> 대상: 인프라 배포 담당자. KafkaConnect는 `spec.image`로 **커스텀 이미지**를 참조한다. 이미지에는
> Debezium 커넥터·JDBC sink·드라이버에 더해 **timestamptz 커스텀 컨버터**(#425)가 동봉된다.

## 왜 커스텀 이미지인가

- Postgres `timestamptz` 컬럼은 Debezium이 `time.precision.mode`와 무관하게 `io.debezium.time.ZonedTimestamp`(ISO **문자열**)로 방출한다. Confluent JDBC sink가 이를 varchar로 적재해 `timestamp with time zone` 컬럼에 INSERT가 실패한다(#425, Confluent JDBC #921).
- source 단계에서 `timestamptz`를 **Connect Timestamp** 논리 타입으로 바꾸는 커스텀 컨버터(`com.bifrost.connect.converter.TimestamptzConverter`)로 해결한다. 컨버터 JAR은 Connect 이미지의 plugin 디렉토리에 들어가야 한다.
- 과거 `spec.build`(in-cluster Kaniko)는 plugin artifact를 **URL로만** 받는다. repo가 private이라 컨버터 JAR을 URL로 줄 수 없어 → 이미지를 직접 빌드하는 `spec.image`로 일원화했다.

## 구성요소

| 항목 | 경로 |
| --- | --- |
| 컨버터 모듈(TDD) | `connect-plugins/timestamptz-converter/` |
| 이미지 정의(멀티스테이지) | `infra/docker/kafka-connect/Dockerfile` |
| CI 빌드 | `Jenkinsfile` → `Build Kafka Connect image` 스테이지 |
| KafkaConnect CR | `infra/k8s/kafka/kafka-connect.yaml` (`spec.image`) |
| 커넥터 config 등록 | `SourceDebeziumConnectorMapper`(Postgres에 `converters=timestamptz` + `timestamptz.type`) |

이미지 = `harbor.harbor.svc.cluster.local/library/bifrost-kafka-connect:<tag>`.
포함 플러그인 버전은 Dockerfile의 `ARG`로 고정(debezium 3.5.1.Final, JDBC sink 10.9.4, postgres 42.7.4, mariadb 3.4.1).

## 빌드

### A. CI(Jenkins) — 정석 루트

`main`에 `infra/docker/kafka-connect/**` 또는 `connect-plugins/**` 변경이 머지되면 `Build Kafka Connect image` 스테이지가 Kaniko로 빌드해 Harbor에 push한다:

- `:${CONNECT_TAG}` — KafkaConnect `spec.image`가 참조하는 **고정 태그**(Jenkinsfile env, 현재 `1.0.0-converter`). 매니페스트와 같은 값.
- `:<git-sha>` — 추적용. `:latest` — 편의용.

**자동 롤아웃은 하지 않는다.** kafka 매니페스트는 ArgoCD 대상이 아니라 **수동 apply**다(gitops chart-bump 흐름과 별개). 빌드 후 아래 롤아웃 절차를 수행한다. 이미지를 바꿀 땐 Jenkinsfile `CONNECT_TAG`와 매니페스트 `spec.image` 태그를 **함께 bump**한다.

### B. 수동 — 최초 컷오버/긴급

```bash
# 레포 루트에서 (멀티모듈 Gradle로 컨버터 JAR을 함께 빌드)
docker build -f infra/docker/kafka-connect/Dockerfile \
  -t harbor.harbor.svc.cluster.local/library/bifrost-kafka-connect:1.0.0-converter .
docker push harbor.harbor.svc.cluster.local/library/bifrost-kafka-connect:1.0.0-converter
```

> in-cluster Harbor가 외부에서 안 보이면, Kaniko Job(레포 루트 컨텍스트 + 위 Dockerfile + `harbor-push-secret`)으로 클러스터 안에서 빌드·push 한다. registries.conf insecure 설정은 기존 `buildah-registries` ConfigMap 참조.

## 롤아웃

1. `kafka-connect.yaml`의 `spec.image` 태그가 빌드한 태그와 같은지 확인(현재 `:1.0.0-converter`). 새 태그면 매니페스트도 함께 올린다(Strimzi는 image **문자열**이 바뀌어야 롤링한다 — `:latest` 고정은 롤을 트리거하지 않음).
2. 적용:
   ```bash
   kubectl apply -f infra/k8s/kafka/kafka-connect.yaml
   kubectl -n platform-kafka rollout status statefulset/platform-connect-connect --timeout=300s
   ```
3. 이미지에 컨버터가 들어갔는지 확인:
   ```bash
   kubectl -n platform-kafka exec platform-connect-connect-0 -- \
     sh -c 'ls /opt/kafka/plugins/debezium-postgres | grep timestamptz'
   ```
4. 기존 Postgres source 커넥터에 컨버터 config를 반영(새로 만든 파이프라인은 매퍼가 자동 주입). 라이브 커넥터는 config 패치(`converters=timestamptz` + `timestamptz.type=...`) 후 재시작한다. **Debezium 규약상 타입 키는 `<alias>.type`** (`converters.<alias>.type` 아님 — 잘못 쓰면 컨버터 null → task FAILED, #462):
   ```bash
   kubectl -n platform-kafka patch kafkaconnector <source-connector> --type merge \
     -p '{"spec":{"config":{"converters":"timestamptz","timestamptz.type":"com.bifrost.connect.converter.TimestamptzConverter"}}}'
   kubectl -n platform-kafka annotate kafkaconnector <source-connector> \
     strimzi.io/restart="true" --overwrite
   ```
5. sink task 회복 확인(타입 불일치 에러 사라짐):
   ```bash
   kubectl -n platform-kafka get kafkaconnector <sink-connector> \
     -o jsonpath='{.status.connectorStatus.tasks[*].state}'
   ```

## 주의

- **정밀도**: Connect Timestamp는 millisecond 해상도. timestamptz의 microsecond 이하는 절삭된다(instant는 보존). ms로 충분한 데이터 전제.
- **Postgres 전용**: 컨버터는 `timestamptz` 타입에만 등록된다. MariaDB 커넥터에는 달지 않는다.
- 플러그인 버전을 바꾸려면 Dockerfile의 `ARG`만 고치고 재빌드 → 매니페스트 태그 bump → 롤아웃.
