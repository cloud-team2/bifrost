# EDA 파이프라인 테스트 가이드

EDA(Event-Driven Architecture, 내부 패턴명 `FAN_OUT`) 파이프라인이 올바르게 동작하는지
**무엇을 / 어느 레벨에서 / 어떻게** 검증하는지 정리한다. 클러스터 기동·풀스택 셋업 같은 공통 절차는
중복하지 않고 기존 문서를 참조한다:

- 로컬 셋업(Track A/B): [`local-dev-fullstack.md`](./local-dev-fullstack.md)
- 자동 E2E 스모크(EKS/kind): [`pipeline-e2e-smoke.md`](./pipeline-e2e-smoke.md) · 스크립트 [`scripts/pipeline-e2e-smoke.sh`](../../scripts/pipeline-e2e-smoke.sh)

---

## 1. EDA란 / 무엇을 검증하나

EDA는 **source DB의 변경을 Kafka 토픽에 발행**하고, 여러 외부 컨슈머가 각자 구독하는 1:N 패턴이다.
CDC(`DIRECT`)와 달리 **sink DB가 없고**, Bifrost가 만드는 커넥터는 **source 1개뿐**이다.

```
source DB ──(Debezium source connector)──▶ Kafka topic ──▶ 외부 컨슈머 A, B, C…
```

검증의 핵심은 다음 4가지다.

1. 파이프라인 생성 시 **source 커넥터 1개**만 프로비저닝된다(sink 커넥터 없음).
2. source 커넥터가 **RUNNING**으로 전이하고, 파이프라인 상태가 `creating → active`가 된다.
3. **토픽이 자동 생성**된다: `eda.table.{projectKey}.{dbSlug}.{schema}.{table}` (`dbSlug={dbName}-{datasourceId 앞 8 hex}`).
4. source 테이블에 변경(INSERT 등)을 넣으면 **해당 토픽에 메시지가 발행**된다.

## 2. EDA vs CDC 차이

| 항목 | EDA (`FAN_OUT`) | CDC (`DIRECT`) |
| --- | --- | --- |
| 커넥터 | source 1개 | source + sink 2개 |
| sink DB | 없음(`sinkDbId=null`) | 필수 |
| 생성 단계(UI) | 4단계(Sink DB 없음) | 5단계 |
| 컨슈머 | 외부(Bifrost 비소유) | sink 커넥터(Bifrost 소유) |
| lag 추적/lag 상태 | 비대상 | 대상(임계치 초과 시 `lag`) |
| 데이터 흐름 | source→topic | source→topic→sink DB |

> 토픽 root는 패턴별로 다르다: EDA(`FAN_OUT`)는 `eda.table`, CDC(`DIRECT`)는 `cdc.table`을 쓴다.

## 3. 테스트 레벨 (피라미드)

| 레벨 | 무엇 | K8s | 비용 |
| --- | --- | --- | --- |
| L1 단위/통합 | 도메인 로직(상태전이·프로비저닝 호출·삭제) | 불필요(mock) | 낮음 |
| L2 로컬 Track A | 로그인→DB등록→스키마조회→**생성 UI** 흐름 | 불필요 | 낮음 |
| L3 로컬 Track B | **실제 source→topic 발행**까지 | kind+Strimzi | 중간 |
| L4 자동 E2E 스모크 | 생성→커넥터 RUNNING→토픽 생성 자동 판정 | kind/EKS | 중간 |

대부분의 회귀는 L1으로 잡고, "실제로 토픽에 흐르는가"는 L3/L4로 확인한다.

---

## L1. 단위/통합 테스트

mock provisioner로 EDA 생성/상태전이/삭제를 검증한다(K8s 불필요).

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  ./gradlew :services:operations-backend:test --tests '*PipelineEdaIntegrationTest'
```

커버 시나리오(`PipelineEdaIntegrationTest`):
- EDA 생성 → `creating` + **source 커넥터만** 프로비저닝
- watcher: source RUNNING → `active`, source FAILED → `error`
- EDA 삭제 → `KafkaResourceCleaner`가 `FAN_OUT`으로 정리 호출

## L2. 로컬 Track A — UI·도메인 흐름 (K8s 불필요)

[`local-dev-fullstack.md` 트랙 A](./local-dev-fullstack.md) 셋업(`docker compose up -d meta-db tenant-postgres tenant-mariadb` + backend + frontend) 후:

1. 로그인 → 워크스페이스 선택
2. **Databases → Register** 로 source(Postgres `5434`) 등록 → **CDC readiness** 확인
3. **Pipeline → 새 파이프라인 → 연결 방식 = EDA** 선택 → 단계가 **4단계(Sink DB 없음)** 인지 확인
4. 테이블 선택 → 생성 시도

> ⚠️ Track A는 K8s가 없어 **생성 자체는 실패**(프로비저닝 단계)한다. 여기서 검증하는 건
> **EDA 분기 UI(4단계·sink 미요구)·스키마 조회·테이블 readiness**까지다. 실제 발행은 Track B로.

## L3. 로컬 Track B — 실제 source→topic 발행 (kind + Strimzi)

[`local-dev-fullstack.md` 트랙 B](./local-dev-fullstack.md)로 kind+Strimzi+Connect를 올린 뒤(이미 떠 있으면 생략),
백엔드를 kind kubeconfig로 기동한다. 그다음 EDA 파이프라인을 만들고 발행을 확인한다.

```bash
# 1) source(pg) 등록 후 EDA 파이프라인 생성 (UI 또는 API)
#    UI: 연결 방식 EDA → source 선택 → 테이블 선택 → 생성
#    API 예시는 local-dev-fullstack.md B6 참고(sink 필드 없이 pattern=FAN_OUT)

# 2) source 커넥터 RUNNING 확인 (sink 커넥터는 없어야 정상)
kubectl -n platform-kafka get kafkaconnector | grep "<pipelineId>"
#   → <pipelineId>-source 만 존재, READY=True

# 3) 토픽 자동 생성 확인
kubectl -n platform-kafka exec platform-kafka-kafka-0 -- \
  bin/kafka-topics.sh --bootstrap-server localhost:9092 --list | grep "eda.table."

# 4) source에 변경 주입 → 토픽에 메시지 발행 확인
docker exec -i tenant-postgres psql -U debezium -d testdb \
  -c "INSERT INTO orders(customer,amount,status) VALUES('eda-test',1,'paid');"

kubectl -n platform-kafka exec platform-kafka-kafka-0 -- \
  bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic eda.table.<projectKey>.<dbSlug>.public.orders --from-beginning --max-messages 1 --timeout-ms 10000
#   → op:'c' + after 페이로드가 보이면 GREEN
```

## L4. 자동 E2E 스모크

생성→커넥터 RUNNING→토픽 생성까지 자동 판정한다(EKS 또는 kind). 컨텍스트는 `KUBECONFIG`으로 지정.

```bash
./scripts/pipeline-e2e-smoke.sh eda      # EDA만
./scripts/pipeline-e2e-smoke.sh all      # EDA + CDC
```

`run_eda`가 하는 일: `POST /internal/pipelines`(pattern `FAN_OUT`) → `{pipelineId}-source` RUNNING 대기 →
토픽 `eda.table.{PROJECT_KEY}.{SRC_DB}-{SRC_DATASOURCE_ID 앞 8 hex}.{SRC_SCHEMA}.{SRC_TABLE}` 존재 확인 → `EDA E2E GREEN`.
전제 조건(Kafka/Connect Ready, backend health 등)은 [`pipeline-e2e-smoke.md`](./pipeline-e2e-smoke.md) 참고.

---

## EDA 고유 검증 체크리스트

- [ ] 생성 직후 파이프라인 상태 `creating`
- [ ] **source 커넥터 1개만** 생성(sink 커넥터 없음)
- [ ] source 커넥터 RUNNING → 파이프라인 `active`
- [ ] 토픽 `eda.table.{projectKey}.{dbSlug}.{schema}.{table}` 자동 생성
- [ ] source INSERT → 토픽에 `op:'c'` 메시지 발행
- [ ] (해당 시) source 커넥터 FAILED → 파이프라인 `error` 전이
- [ ] 삭제 시 source 커넥터 + 토픽 정리(orphan 없음)
- [ ] lag 상태/`Sync` 탭은 EDA에 비표시(또는 N/A)인지

## 정리(teardown)

```bash
# 파이프라인 삭제(UI 또는 API) → 커넥터/토픽 정리 확인
kubectl -n platform-kafka get kafkaconnector    # 해당 pipelineId 커넥터 사라졌는지
# 로컬 인프라 정리
docker compose down            # 컨테이너만
docker compose down -v          # 볼륨까지(샘플/메타DB 초기화)
```

## 트러블슈팅

| 증상 | 확인 |
| --- | --- |
| source 커넥터가 RUNNING이 안 됨 | `kubectl -n platform-kafka logs platform-connect-connect-0`, source DB 도달성(LAN IP), CDC readiness |
| 토픽이 안 생김 | 커넥터 상태 FAILED 여부, Debezium 권한/`wal_level=logical`(PG) |
| sink 커넥터가 생김 | EDA인데 `sinkDbId`가 들어갔는지(생성 요청 검증) — EDA는 sink 없어야 정상 |
| 토픽에 메시지가 없음 | source 테이블에 실제 변경을 넣었는지, 약 5% 샘플링은 **trace**에만 적용(데이터는 전량 발행) |
