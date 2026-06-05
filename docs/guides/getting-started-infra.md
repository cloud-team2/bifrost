# 인프라 환경 가이드

Sprint 1 인프라 구성 요소와 팀원 접속 방법을 정리합니다.

## 목차

- [전제 조건](#전제-조건)
- [EKS 접근 설정](#eks-접근-설정)
- [Kafka 접속 정보](#kafka-접속-정보)
- [MetaDB 접속 정보](#metadb-접속-정보)
- [로컬 개발 환경](#로컬-개발-환경)
- [컴포넌트 구조](#컴포넌트-구조)

---

## 전제 조건

```bash
# 필수 도구
brew install awscli kubectl helm terraform

# AWS 프로파일 설정 (팀 공통)
aws configure --profile skala_student
# AWS Access Key ID, Secret Access Key 입력
# Region: ap-northeast-2
```

---

## EKS 접근 설정

```bash
# kubeconfig 업데이트
aws eks update-kubeconfig \
  --region ap-northeast-2 \
  --name skala3-cloud1-finalproj-team2 \
  --profile skala_student

# 접근 확인
kubectl get nodes
kubectl get pod -A
```

**클러스터 정보**

| 항목 | 값 |
|------|-----|
| 클러스터 이름 | `skala3-cloud1-finalproj-team2` |
| 리전 | `ap-northeast-2` (서울) |
| K8s 버전 | 1.35 |
| 노드 | t3.large × 3 |

---

## Kafka 접속 정보

### 클러스터 내부 (서비스 간 통신)

```
Bootstrap Server: platform-kafka-kafka-bootstrap.platform-kafka.svc.cluster.local:9092
```

### 상태 확인

```bash
# Kafka 클러스터
kubectl get kafka -n platform-kafka

# KafkaConnect
kubectl get kafkaconnect -n platform-kafka

# 토픽 목록
kubectl exec -n platform-kafka platform-kafka-kafka-0 -- \
  bin/kafka-topics.sh --bootstrap-server localhost:9092 --list

# CDC 이벤트 실시간 확인 (PostgreSQL)
kubectl exec -n platform-kafka platform-kafka-kafka-0 -- \
  bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic cdc.postgres.public.<테이블명> \
  --from-beginning

# CDC 이벤트 실시간 확인 (MariaDB)
kubectl exec -n platform-kafka platform-kafka-kafka-0 -- \
  bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic cdc.mariadb.testdb.<테이블명> \
  --from-beginning
```

### CDC 토픽 네이밍 규칙

아래는 **수동/로컬 커넥터** 기준(이 가이드의 예시 connector가 쓰는 `topic.prefix`)이다.

| DB | 토픽 패턴 | 예시 |
|----|----------|------|
| PostgreSQL | `cdc.postgres.<schema>.<table>` | `cdc.postgres.public.users` |
| MariaDB | `cdc.mariadb.<database>.<table>` | `cdc.mariadb.testdb.orders` |

> **플랫폼(operations-backend)이 프로비저닝하는 토픽**은 워크스페이스 격리를 위해 `cdc.table.{projectKey}.{dbName}.{schema}.{table}` 규칙을 따른다([ADR 0002](../adr/0002-multi-tenancy-model.md), [design provisioning](../design/backend-springboot/provisioning.md#2-provisioning)). 위 단순 prefix는 로컬 검증용이다.

### Debezium 커넥터 관리

```bash
# 커넥터 목록
kubectl get kafkaconnector -n platform-kafka

# 새 커넥터 등록 (예시: infra/k8s/kafka/04-connectors.yaml 참고)
kubectl apply -f infra/k8s/kafka/<connector>.yaml

# 커넥터 상태 확인
kubectl exec -n platform-kafka platform-connect-connect-0 -- \
  curl -s http://localhost:8083/connectors/<name>/status | python3 -m json.tool
```

---

## MetaDB 접속 정보

플랫폼 내부 메타데이터 저장소 (파이프라인 설정, 워크스페이스 정보 등)

### 클러스터 내부

```
Host: metadb-service.metadb.svc.cluster.local
Port: 5432
Database: metadb
User: platform
Password: (infra/k8s/metadb/01-secret.yaml 참고)
```

### 포트포워딩으로 로컬 접속

```bash
kubectl port-forward svc/metadb-service 5432:5432 -n metadb

# 다른 터미널에서
psql -h localhost -U platform -d metadb
```

---

## 로컬 개발 환경

서비스 개발 및 테스트용 로컬 환경입니다. EKS 없이 동일한 구성을 로컬에서 실행합니다.

### 실행

```bash
docker compose up -d
```

### 컨테이너별 접속 정보

| 서비스 | 로컬 포트 | 용도 |
|--------|-----------|------|
| MetaDB (PostgreSQL) | `localhost:5433` | 플랫폼 메타DB |
| UserDB PostgreSQL | `localhost:5434` | CDC 소스 DB (user: debezium / pw: debezium / db: testdb) |
| UserDB MariaDB | `localhost:3307` | CDC 소스 DB (user: debezium / pw: debezium / db: testdb) |
| Kafka | `localhost:9094` | 메시지 브로커 |
| Kafka Connect REST | `localhost:8083` | 커넥터 관리 API |
| Kafka UI | `localhost:8090` | 토픽/메시지 웹 UI |

### Kafka Connect 커넥터 등록 (로컬)

```bash
# PostgreSQL CDC 커넥터
curl -X POST http://localhost:8083/connectors \
  -H "Content-Type: application/json" \
  -d '{
    "name": "debezium-postgres-local",
    "config": {
      "connector.class": "io.debezium.connector.postgresql.PostgresConnector",
      "topic.prefix": "cdc.postgres",
      "database.hostname": "tenant-postgres",
      "database.port": "5432",
      "database.user": "debezium",
      "database.password": "debezium",
      "database.dbname": "testdb",
      "plugin.name": "pgoutput",
      "slot.name": "debezium_slot",
      "publication.autocreate.mode": "all_tables",
      "schema.history.internal.kafka.bootstrap.servers": "kafka:9092",
      "schema.history.internal.kafka.topic": "schema-changes.postgres"
    }
  }'

# MariaDB CDC 커넥터
curl -X POST http://localhost:8083/connectors \
  -H "Content-Type: application/json" \
  -d '{
    "name": "debezium-mariadb-local",
    "config": {
      "connector.class": "io.debezium.connector.mariadb.MariaDbConnector",
      "topic.prefix": "cdc.mariadb",
      "database.hostname": "tenant-mariadb",
      "database.port": "3306",
      "database.user": "debezium",
      "database.password": "debezium",
      "database.server.id": "1",
      "database.include.list": "testdb",
      "schema.history.internal.kafka.bootstrap.servers": "kafka:9092",
      "schema.history.internal.kafka.topic": "schema-changes.mariadb"
    }
  }'
```

### 종료

```bash
docker compose down          # 컨테이너만 종료 (데이터 유지)
docker compose down -v       # 컨테이너 + 볼륨 삭제
```

---

## 컴포넌트 구조

```
EKS (ap-northeast-2)
├── strimzi-system
│   └── strimzi-cluster-operator     # Strimzi 1.0.0 오퍼레이터
└── platform-kafka
    ├── platform-kafka-kafka-0/1/2   # Kafka 4.2.0 KRaft (broker+controller)
    ├── platform-kafka-entity-operator
    │   ├── topic-operator           # KafkaTopic CRD 처리
    │   └── user-operator            # KafkaUser CRD 처리
    ├── platform-connect-connect-0   # Kafka Connect (현재 replicas 1, 목표 2; Debezium)
    │   ├── debezium-postgres        # PostgreSQL CDC 커넥터 (예정, 현재 EKS에 CR 미생성)
    │   └── debezium-mariadb         # MariaDB CDC 커넥터 (예정, 현재 미생성)

metadb
└── metadb                           # PostgreSQL 15 (플랫폼 메타DB)

tenantdb
├── tenant-postgres                    # PostgreSQL 15 (CDC 소스, wal_level=logical)
└── tenant-mariadb                     # MariaDB 10.11 (CDC 소스, binlog ROW)
```

---

## 자주 쓰는 명령어

```bash
# 전체 상태 한눈에 보기
kubectl get pod -A

# Kafka Connect 로그
kubectl logs -n platform-kafka -l strimzi.io/cluster=platform-connect -f

# MetaDB 직접 접속
kubectl exec -it -n metadb deployment/metadb -- psql -U platform -d metadb

# UserDB PostgreSQL 접속
kubectl exec -it -n tenantdb deployment/tenant-postgres -- psql -U debezium -d testdb

# UserDB MariaDB 접속
kubectl exec -it -n tenantdb deployment/tenant-mariadb -- mariadb -u debezium -pdebezium testdb
```
