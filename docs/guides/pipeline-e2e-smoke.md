# EDA/CDC 파이프라인 real E2E smoke (#76 갱신)

operations-backend가 실제 EKS 클러스터에서 EDA/CDC 파이프라인을 생성하고 KafkaConnector가 정상
동작하는지 판정하는 smoke 절차다. provisioner는 Strimzi 단일 경로이므로 `KUBECONFIG`만 대상 클러스터로
지정하면 된다(별도 모드 플래그 없음). 자동화 스크립트는 [`scripts/pipeline-e2e-smoke.sh`](../../scripts/pipeline-e2e-smoke.sh).

## 전제 조건

| 항목 | 기대 상태 | 확인 방법 |
| --- | --- | --- |
| Kafka 클러스터 `platform-kafka` | Ready (KRaft, 3 브로커) | `kubectl get kafka -n platform-kafka` |
| KafkaConnect `platform-connect` | `status.conditions[Ready]=True` | `kubectl get kafkaconnect -n platform-kafka` |
| KafkaConnect 플러그인 | Debezium PG/MariaDB + JDBC Sink 포함 | `kubectl get kafkaconnect platform-connect -n platform-kafka -o jsonpath='{.status.connectorPlugins[*].class}'` |
| operations-backend | `KUBECONFIG`을 대상 클러스터로 지정해 기동, `/actuator/health` → UP | `curl http://localhost:8080/actuator/health` |
| Source DB `secretRef` | `SecretStore`에 등록된 secretRef | DB 등록 API 호출 후 반환값 사용 |
| `connectors` 테이블 | V4 마이그레이션 적용 | 앱 기동 시 Flyway 자동 실행 |

## 기동 및 포트포워딩

### 로컬 개발 (kubeconfig 직접 사용)

```bash
# MetaDB 포트포워딩
kubectl port-forward svc/metadb-service -n metadb 5433:5432 &

# operations-backend 기동 (KUBECONFIG으로 대상 클러스터 지정)
META_DB_URL=jdbc:postgresql://localhost:5433/metadb \
KUBECONFIG=~/.kube/config-skala_student \
./gradlew :services:operations-backend:bootRun
```

### EKS 배포 후 포트포워딩

```bash
kubectl -n bifrost-system port-forward deploy/operations-backend 8080:8080
```

## SecretStore seed (InMemorySecretStore, DB 등록 API 완료 전)

DB 등록 API(`POST /api/v1/workspaces/{wsId}/databases`)가 완성되면 해당 API에서
`secretRef`를 받아 사용한다. 그 전까지는 앱 기동 시 ApplicationRunner로 seed:

```java
// 임시 seed — DB 등록 API 완성 후 제거
@Bean @Profile("dev")
ApplicationRunner seedSecrets(SecretStore store) {
    return args -> {
        String srcRef = store.put(
            new SecretContext(UUID.randomUUID(), "smoke-src"),
            new DbCredential("postgres_user", "postgres_password"));
        String sinkRef = store.put(
            new SecretContext(UUID.randomUUID(), "smoke-sink"),
            new DbCredential("mariadb_user", "mariadb_password"));
        System.out.println("SRC_SECRET_REF=" + srcRef);
        System.out.println("SINK_SECRET_REF=" + sinkRef);
    };
}
```

## 실행

```bash
# EDA smoke
BASE_URL=http://localhost:8080 \
PROJECT_KEY=smoke \
SRC_HOST=tenantdb-postgres.tenantdb.svc.cluster.local \
SRC_DB=shop SRC_SCHEMA=public SRC_TABLE=orders \
SRC_SECRET_REF=<앱 기동 시 출력된 srcRef> \
./scripts/pipeline-e2e-smoke.sh eda

# CDC smoke
BASE_URL=http://localhost:8080 \
PROJECT_KEY=smoke \
SRC_SECRET_REF=<srcRef> SINK_SECRET_REF=<sinkRef> \
./scripts/pipeline-e2e-smoke.sh cdc

# 전체
./scripts/pipeline-e2e-smoke.sh all
```

종료 코드: `0`=GREEN, `1`=RED, `2`=전제/도구 오류

## GREEN/RED 판정 기준

상태→파이프라인 매핑과 임계값은 기능명세서 부록 B를 단일 출처로 따른다.

| 시나리오 | GREEN 조건 | RED 조건 |
| --- | --- | --- |
| 공통 | `POST /internal/pipelines` → 202 Accepted | 422 (stage/errorCode 출력) |
| EDA | `{pipelineId}-source` RUNNING + topic 생성 | FAILED 또는 timeout |
| CDC | source + sink 모두 RUNNING + topic 생성 | 어느 하나라도 FAILED/timeout |

timeout 기본값: 90초 (`TIMEOUT_SEC` 환경변수로 조정).

## RED 시 점검 포인트

```bash
# 1. connector 상태 조회
kubectl get kafkaconnector -n platform-kafka
kubectl describe kafkaconnector <pipelineId>-source -n platform-kafka

# 2. KafkaConnect 로그
kubectl logs -n platform-kafka platform-connect-connect-0 --tail=100

# 3. stage/errorCode 확인 (canonical 경로)
curl "http://localhost:8080/internal/ops/projects/smoke/kafka/connectors/<connectorName>/status"

# 4. SecretStore seed 확인 (앱 기동 로그에 SRC_SECRET_REF 출력됨)
# 5. KafkaConnect 플러그인 확인
kubectl get kafkaconnect platform-connect -n platform-kafka \
  -o jsonpath='{.status.connectorPlugins[*].class}' | tr ' ' '\n'
```

## 데이터 흐름 검증 (optional, connector RUNNING 후)

```bash
# EDA: source PostgreSQL에 row insert → topic 메시지 확인
PGPASSWORD=<password> psql -h <src_host> -U <user> -d shop \
  -c "INSERT INTO public.orders (id, status) VALUES (gen_random_uuid(), 'test');"

# topic consumer (KafkaConnect pod 내부)
kubectl -n platform-kafka exec platform-connect-connect-0 -- \
  bin/kafka-console-consumer.sh \
  --bootstrap-server platform-kafka-kafka-bootstrap:9092 \
  --topic eda.table.smoke.shop-11111111.public.orders --from-beginning --max-messages 1

# CDC: sink MariaDB에서 upsert 반영 확인
mysql -h <sink_host> -u <user> -p<password> warehouse \
  -e "SELECT * FROM orders LIMIT 5;"
```

---

## 정재환 인계 메모 (백강민 금요일 부재)

> 백강민이 금요일에 없으므로 아래 잔여 작업은 **정재환**이 인수한다.

### 인계 항목

| 항목 | 현황 | 인수 내용 |
| --- | --- | --- |
| **SecretStore seed** | DB 등록 API 미완성 → InMemorySecretStore 수동 seed 필요 | DB 등록 API 완성 후 secretRef를 smoke에 연결 |
| **JDBC Sink 플러그인** | `platform-connect`에 JDBC Sink 아직 미포함 | `chore/#44` PR merge 후 KafkaConnect rebuild 확인 |
| **Kafka ACL** | TenantProvisioner에서 authorization 섹션 제거 상태 | 클러스터 AclAuthorizer 활성화 후 재추가 |
| **real CDC full E2E** | SecretStore seed 필요 | seed 완료 후 `./scripts/pipeline-e2e-smoke.sh cdc` 실행 |
| **Helm 배포** | values.yaml 수정됨(`chore/#75` PR) | bifrost-system NS 배포 및 ServiceAccount 권한 확인 |

### 빠른 시작 체크리스트

```text
[ ] kubectl port-forward svc/metadb-service -n metadb 5433:5432
[ ] KUBECONFIG=~/.kube/config-skala_student 로 앱 기동
[ ] 앱 기동 로그에서 KafkaConnectorWatcher 시작 확인 (WARN 없어야 함)
[ ] SRC_SECRET_REF 확보 (DB 등록 API 또는 임시 seed)
[ ] ./scripts/pipeline-e2e-smoke.sh eda → GREEN 확인
[ ] ./scripts/pipeline-e2e-smoke.sh cdc → GREEN 확인
[ ] 결과를 GitHub 이슈 #76 댓글에 붙이기
```

### 관련 이슈

- `#75` — Helm values, RBAC, KafkaConnect replication factor
- `#76` — smoke 스크립트 및 이 runbook
- `#77` — canonical 상태 조회 API (`/internal/ops/projects/.../kafka/connectors/.../status`)
- `#78` — MariaDB schema history 설정, JDBC Sink SMT 보강
