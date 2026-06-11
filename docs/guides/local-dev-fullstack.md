# 로컬 풀스택 연동 가이드 (Frontend + operations-backend)

> 로컬에서 핵심 흐름(로그인 → 워크스페이스 → DB 등록 → 스키마 조회 → **파이프라인 생성 → 실제 CDC 복제**)을
> 구동·검증한다. 파이프라인 provisioner는 **Strimzi(K8s) 단일 경로**다(mock 제거). 따라서 파이프라인을
> 만들려면 로컬 K8s(**kind + Strimzi**)가 필요하다.
>
> 가이드는 두 트랙으로 나뉜다:
> - **트랙 A — UI·도메인 흐름**: metadb + user DB + backend + frontend만으로 로그인/워크스페이스/DB 등록/
>   스키마 조회/연결 테스트까지. **K8s 불필요.**
> - **트랙 B — 실제 CDC 파이프라인**: kind+Strimzi+Connect 위에서 Postgres→MariaDB CDC를 실제로 복제. EKS와
>   동일한 메커니즘(KafkaConnector CR)을 로컬 축소판에서 검증한다. EKS용 real smoke는
>   [pipeline-e2e-smoke.md](./pipeline-e2e-smoke.md) 참조.

## 0. 사전 준비

- Docker Desktop (K8s 활성화 권장), **JDK 21 (Temurin)**, Node 20+
- JDK 경로: `export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home`
- 트랙 B 추가: `kubectl`, `helm` (kind 없이 Docker Desktop K8s 사용 가능)

---

## 트랙 A — UI·도메인 흐름 (K8s 불필요)

### A1. 인프라 기동 (metadb + 등록 대상 user DB)

```bash
docker compose up -d meta-db tenant-postgres tenant-mariadb
```

| 컨테이너 | 호스트 포트 | 계정 / DB | 용도 |
| --- | --- | --- | --- |
| `meta-db` | `5433` | platform / platform / `metadb` | 백엔드 메타DB(Flyway) |
| `tenant-postgres` | `5434` | debezium / debezium / `testdb` | 등록할 소스 DB(`wal_level=logical`) |
| `tenant-mariadb` | `3307` | debezium / debezium / `testdb` | CDC sink/소스(`binlog ROW`) |

- 최초 기동 시 `infra/local/tenantdb-init/`의 샘플 스키마(`public.orders`, `public.customers` 등)가 주입된다 → 테이블 선택/스키마 조회가 바로 동작.
- 샘플 테이블이 안 보이면 볼륨이 남아있는 것: `docker compose down -v` 후 다시 `up`.

### A2. 백엔드 기동 (operations-backend)

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home
./gradlew :services:operations-backend:bootRun
```

- 기본 프로파일 `dev`, `secret-store.provider=db`(metadb `secrets` 테이블 영속) — 추가 env 불필요.
- 기동 시 **Flyway 자동 적용 + JPA `validate`** 후 `:8080`.
- **dev 데모 계정 자동 seed**: `ta@bifrost.io` / `ta123456` (워크스페이스 `Demo Team`).
- AdminClient(`localhost:9092`)가 연결 실패 WARN 로그를 출력하지만 앱은 정상 기동한다. Kafka 기능(topic-info, consumer-groups, messages, metrics rate)은 Kafka 접근 시에만 동작.
- K8s가 없으면 워크스페이스 프로비저닝(`TenantProvisioner`)·**파이프라인 생성은 실패**한다(워크스페이스는 PROVISIONING으로 남음). UI·DB 등록·스키마 조회는 영향 없음. 파이프라인까지 보려면 **트랙 B**로.

### A3. 프론트엔드 기동

```bash
cd services/frontend
npm install
npm run dev      # http://localhost:5173, /api → localhost:8080 프록시(vite)
```

### A4. 클릭 경로 (브라우저)

1. `http://localhost:5173` → **로그인** `ta@bifrost.io` / `ta123456`
2. `Demo Team` 선택(또는 **New Project**로 워크스페이스 추가 — 소유 기반 다중)
3. **Databases → Register a Database** → PostgreSQL host `localhost`, port `5434`, db `testdb`, user/pw `debezium` → **Test Connection** → **Register & Check**(CDC readiness)
4. DB 상세 → **Tables** 탭에서 실제 스키마 조회

> connection-test / CDC readiness / 스키마 조회는 **실제 대상 DB에 접속**하므로 tenant-postgres/mariadb가 떠 있어야 한다.

---

## 트랙 B — 실제 CDC 파이프라인 (kind + Strimzi)

> 결과: source(Postgres)에 넣은 변경이 Debezium→Kafka→JDBC sink를 거쳐 **sink(MariaDB)에 실제로 복제**된다.
> 매니페스트·스크립트는 [`infra/local/k8s/`](../../infra/local/k8s/)에 있다(EKS용 `infra/k8s/kafka/*`의 축소판).

### B1. K8s 준비 + Strimzi 1.0.0 설치

**Docker Desktop K8s** (권장, kind 불필요):
```bash
# Docker Desktop → Settings → Kubernetes → Enable Kubernetes
kubectl create namespace platform-kafka

helm repo add strimzi https://strimzi.io/charts/ && helm repo update
helm install strimzi-operator strimzi/strimzi-kafka-operator \
  --namespace platform-kafka --version 1.0.0 \
  --set watchNamespaces="{platform-kafka}"
kubectl -n platform-kafka rollout status deploy/strimzi-cluster-operator
```

**kind 사용 시** (대안):
```bash
kind create cluster --name bifrost
kubectl create namespace platform-kafka
helm repo add strimzi https://strimzi.io/charts/ && helm repo update
helm install strimzi-operator strimzi/strimzi-kafka-operator \
  --namespace platform-kafka --version 1.0.0 \
  --set watchNamespaces="{platform-kafka}"
```

> Strimzi 1.0.0 URL 설치(`strimzi.io/install/1.0.0?...`)는 404 오류 발생 — Helm 사용 필수.

### B2. Kafka(축소판) 기동

```bash
kubectl apply -f infra/local/k8s/kafka-kind.yaml      # 1브로커·ephemeral·RF1/ISR1
kubectl -n platform-kafka wait kafka/platform-kafka --for=condition=Ready --timeout=300s
```

### B3. Kafka Connect 이미지 빌드 + 적재 + 기동

Connect 이미지엔 Debezium(PostgreSQL/MariaDB) + Confluent JDBC sink + JDBC 드라이버 + timestamptz 커스텀 컨버터(#425)가 들어간다. 컨버터 JAR을 멀티모듈 Gradle로 함께 빌드하므로 **빌드 컨텍스트는 레포 루트**(`.`)다.

```bash
docker build -f infra/local/k8s/Dockerfile.connect-kind -t bifrost/kafka-connect-kind:v1 .

# Docker Desktop: kind load 불필요 (로컬 이미지 직접 참조)
# kind 사용 시: kind load docker-image bifrost/kafka-connect-kind:v1 --name bifrost
#
# ⚠️ Docker Desktop k8s는 containerd 이미지 스토어를 쓴다. 같은 태그(v1)로 재빌드하면
#    containerd가 옛 이미지를 캐시해 안 바뀐다 → 재빌드 시 새 태그를 쓰고
#    KafkaConnect spec.image를 그 태그로 갱신하거나, kind면 다시 load 한다.

kubectl apply -f infra/local/k8s/connect-kind.yaml
kubectl -n platform-kafka wait kafkaconnect/platform-connect --for=condition=Ready --timeout=300s
```

### B4. DB 네트워킹 — LAN IP 사용 (중요)

source/sink DB는 **호스트 백엔드의 연결 테스트**와 **kind 파드 안 커넥터** 양쪽에서 도달해야 한다.
`localhost`는 파드에서 호스트를 못 가리키므로, **둘 다 도달 가능한 맥 LAN IP**로 등록한다.

```bash
ipconfig getifaddr en0      # 예: 10.250.200.188  (Wi-Fi면 en1일 수 있음)
```

### B5. 백엔드를 kind에 연결해 기동

```bash
# 기존 8080 점유 백엔드가 있으면 종료: lsof -ti tcp:8080 | xargs kill
kind get kubeconfig --name bifrost > /tmp/kind-bifrost.kubeconfig

JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
KUBECONFIG=/tmp/kind-bifrost.kubeconfig \
  ./gradlew :services:operations-backend:bootRun
```

기동 로그에 `KafkaConnectorWatcher 시작: namespace=platform-kafka, cluster=platform-connect`가 보이면 정상.

### B6. DB 등록 + CDC 파이프라인 생성 (API)

```bash
B=http://localhost:8080
IP=$(ipconfig getifaddr en0)
P(){ python3 -c "import sys,json;print(json.load(sys.stdin)$1)"; }

TOKEN=$(curl -s -X POST $B/api/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"ta@bifrost.io","password":"ta123456"}' | P "['accessToken']")
WSID=$(curl -s $B/api/v1/workspaces -H "Authorization: Bearer $TOKEN" | P "[0]['id']")
A="Authorization: Bearer $TOKEN"

# source(pg, 5434) / sink(maria, 3307) — host는 반드시 LAN IP
SRC=$(curl -s -X POST $B/api/v1/workspaces/$WSID/databases -H "$A" -H 'Content-Type: application/json' \
  -d "{\"name\":\"pg-cdc\",\"engine\":\"postgresql\",\"host\":\"$IP\",\"port\":5434,\"dbName\":\"testdb\",\"username\":\"debezium\",\"password\":\"debezium\"}" | P "['id']")
SINK=$(curl -s -X POST $B/api/v1/workspaces/$WSID/databases -H "$A" -H 'Content-Type: application/json' \
  -d "{\"name\":\"maria-cdc\",\"engine\":\"mariadb\",\"host\":\"$IP\",\"port\":3307,\"dbName\":\"testdb\",\"username\":\"debezium\",\"password\":\"debezium\"}" | P "['id']")

# CDC(direct): pg public.orders → maria
curl -s -X POST $B/api/v1/workspaces/$WSID/pipelines -H "$A" -H 'Content-Type: application/json' \
  -d "{\"name\":\"orders-cdc\",\"pattern\":\"direct\",\"sourceDbId\":\"$SRC\",\"sinkDbId\":\"$SINK\",\"schema\":\"public\",\"table\":\"orders\"}"
```

상태는 `creating` → 커넥터가 RUNNING이 되면 watcher가 **`active`**로 전이한다.

### B7. 복제 검증 — 스모크 스크립트

source에 변경을 넣고 sink 반영까지 자동 확인한다:

```bash
KUBECONFIG=/tmp/kind-bifrost.kubeconfig ./infra/local/k8s/cdc-smoke-test.sh
```

수동 확인:

```bash
# source(Postgres)에 INSERT
docker exec -i tenant-postgres psql -U debezium -d testdb \
  -c "INSERT INTO orders(customer,amount,status) VALUES('test',1,'paid');"
# sink(MariaDB)에서 1~2초 뒤 확인
docker exec -i tenant-mariadb mariadb -udebezium -pdebezium testdb -e "SELECT * FROM orders ORDER BY id;"
```

커넥터 내부 상태:

```bash
export KUBECONFIG=/tmp/kind-bifrost.kubeconfig
kubectl -n platform-kafka get kafkaconnectors            # READY=True 2개(source/sink)
kubectl -n platform-kafka logs platform-connect-connect-0 -f
```

---

## 주의사항

- **파이프라인 생성엔 kind 필수**: provisioner가 Strimzi 단일 경로라, K8s 없이 파이프라인을 만들면 실패한다(트랙 A는 그 외 흐름).
- **secret-store=mock 은 재시작 시 휘발**: 백엔드를 재기동하면 등록한 DB 자격증명이 사라진다(메타DB의 `secret_ref`만 남음) → 재기동 시 **DB 재등록 후** 파이프라인 생성.
- **토픽 재사용 주의**: 토픽명은 `cdc.table.{projectKey}.{db}.{schema}.{table}`로 파이프라인 id와 무관하다. 같은 source·테이블로 파이프라인을 지웠다 다시 만들면 이전 토픽이 남아있을 수 있다 — 깨끗이 보려면 `kubectl -n platform-kafka exec platform-kafka-kafka-0 -- bin/kafka-topics.sh --bootstrap-server localhost:9092 --delete --topic <토픽명>`.
- 백엔드를 Docker로 띄울 경우 user DB 호스트는 LAN IP 또는 `host.docker.internal`을 쓴다(본 가이드는 호스트에서 `gradlew bootRun`).

## 정리

```bash
docker compose down            # 컨테이너만
docker compose down -v          # 볼륨까지(샘플 데이터/메타DB 초기화)
kind delete cluster --name bifrost   # 트랙 B 로컬 K8s 제거
```
