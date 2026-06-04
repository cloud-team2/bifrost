# 로컬 풀스택 연동 가이드 (Frontend + operations-backend, mock)

> `provisioning.mode=mock` 기준으로 **Kafka·K8s 없이** 핵심 흐름(로그인 → 워크스페이스 → DB 등록 → 파이프라인 생성 → SSE `creating→active`)을 로컬에서 구동·검증한다. real(Strimzi/K8s) 경로는 [pipeline-e2e-smoke.md](./pipeline-e2e-smoke.md) 참조.

## 0. 사전 준비

- Docker Desktop, **JDK 21**, Node 18+
- JDK 경로 예: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`

## 1. 인프라 기동 (metadb + 등록 대상 user DB)

```bash
docker compose up -d meta-db user-postgres user-mariadb
```

| 컨테이너 | 호스트 포트 | 계정 / DB | 용도 |
| --- | --- | --- | --- |
| `meta-db` | `5433` | platform / platform / `metadb` | 백엔드 메타DB(Flyway) |
| `user-postgres` | `5434` | debezium / debezium / `testdb` | 등록할 소스 DB(`wal_level=logical`) |
| `user-mariadb` | `3307` | debezium / debezium / `testdb` | CDC sink/소스(`binlog ROW`) |

- 최초 기동 시 `infra/local/userdb-init/`의 샘플 스키마(`public.orders`, `public.customers` 등)가 주입된다 → 파이프라인 테이블 선택/스키마 조회가 바로 동작.
- 샘플 테이블이 안 보이면 볼륨이 남아있는 것: `docker compose down -v` 후 다시 `up`.
- Kafka/Connect는 mock 경로에 **불필요**(백엔드가 startup에 Kafka로 접속하지 않음). 전부 띄우려면 `docker compose up -d`.

## 2. 백엔드 기동 (operations-backend, mock)

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export KUBECONFIG=""          # 로컬 K8s 미접속(워크스페이스 프로비저닝은 mock 데모에서 생략)
./gradlew :services:operations-backend:bootRun
```

- 기본 프로파일 `dev`, `provisioning.mode=mock`, `secret-store.provider=mock` — 추가 env 불필요.
- 기동 시 **Flyway V1~V7 적용 + JPA `validate`**(엔티티↔스키마 정합 자동 검증) 후 `:8080`.
- **dev 데모 계정 자동 seed**: `ta@bifrost.io` / `ta123456` (워크스페이스 `Demo Team`). 끄려면 `dev.seed.enabled=false`, 값 변경은 `dev.seed.*`.

## 3. 프론트엔드 기동

```bash
cd services/frontend
npm install
npm run dev      # http://localhost:5173, /api → localhost:8080 프록시(vite)
```

## 4. E2E 클릭 경로 (브라우저)

1. `http://localhost:5173` → **로그인** `ta@bifrost.io` / `ta123456`
2. `Demo Team` 선택(또는 **New Project**로 워크스페이스 추가 — 소유 기반 다중)
3. **Databases → Register a Database**
   - PostgreSQL: host `localhost`, port `5434`, db `testdb`, user/pw `debezium` → **Test Connection**(성공) → **Register & Check**(CDC readiness `OK`)
   - (CDC sink용) MariaDB: host `localhost`, port `3307`, db `testdb`, user/pw `debezium`
4. **Pipelines → 파이프라인 연결**
   - EDA(fan-out): Source DB → 테이블(`public.orders` 등 실제 스키마) → 이름 → 생성
   - CDC(direct): Source(pg) → Sink(maria) → 테이블 → 이름 → 생성
   - 상태 `creating` → **SSE로 곧 `active`** 자동 전환
5. (선택) 이벤트: `GET /api/v1/workspaces/{wsId}/events` 에서 생성/전이 로그 확인

## 5. API로 빠른 검증 (선택)

```bash
B=http://localhost:8080
TOKEN=$(curl -s -X POST $B/api/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"ta@bifrost.io","password":"ta123456"}' | python3 -c "import sys,json;print(json.load(sys.stdin)['accessToken'])")
WSID=$(curl -s $B/api/v1/workspaces -H "Authorization: Bearer $TOKEN" | python3 -c "import sys,json;print(json.load(sys.stdin)[0]['id'])")

# 연결 테스트 → 등록 → readiness → 스키마 → 파이프라인 생성
curl -s -X POST $B/api/v1/workspaces/$WSID/databases/connection-test -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"engine":"postgresql","host":"localhost","port":5434,"dbName":"testdb","user":"debezium","password":"debezium"}'

# SSE 수신(별 터미널): pipeline_status_changed 로 creating→active 확인
curl -N "$B/api/v1/workspaces/$WSID/events/stream?access_token=$TOKEN"
```

## 6. 주의사항

- **mock 모드**: 실제 Kafka 토픽/CR은 만들지 않고, `PipelineActivationSimulator`가 watcher 역할로 RUNNING을 통지해 `PipelineStatusService`(단일 writer)가 `active` 전이를 만든다. real 경로는 #75~78 인프라 전제 필요.
- **secret-store=mock 은 재시작 시 휘발**: 백엔드를 재기동하면 등록한 DB 자격증명이 사라진다(메타DB의 `secret_ref`만 남음) → 재기동 시 DB 재등록.
- connection-test / CDC readiness / 스키마 조회는 **실제 대상 DB에 접속**하므로 user-postgres/mariadb가 떠 있어야 한다.
- 백엔드를 Docker로 띄울 경우 user DB 호스트는 `localhost` 대신 `host.docker.internal`을 쓴다(본 가이드는 백엔드를 호스트에서 `gradlew bootRun`).

## 7. 정리

```bash
docker compose down        # 컨테이너만
docker compose down -v      # 볼륨까지(샘플 데이터/메타DB 초기화)
```
