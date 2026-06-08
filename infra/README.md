# Infra

플랫폼의 인프라 정의. Terraform (AWS), Helm chart, K8s 매니페스트.

## 구성

```
infra/
├─ terraform/       AWS 리소스 (VPC, EKS, ECR, Route53)
├─ k8s/             Strimzi, Kafka, Monitoring
├─ helm/            전체 서비스 묶음 (umbrella chart)
└─ docker/          커스텀 Docker 이미지 (kafka-connect 등)
```

## 로컬 개발 환경

`docker-compose.yml`은 프로젝트 루트에 있음 (모든 서비스가 공유).

```bash
# 프로젝트 루트에서
docker-compose up -d
```

띄워지는 것:
- meta-db (5433) — 플랫폼 메타데이터 Postgres
- tenant-postgres (5434) — 사용자 DB 시뮬레이션 (wal_level=logical)
- tenant-mariadb (3307) — 사용자 DB 시뮬레이션 (binlog ROW/FULL)
- kafka (9092, 9094) — Kafka 단일 노드
- kafka-connect (8083) — Debezium plugins 포함
- kafka-ui (8090) — Kafka 모니터링 UI

## AWS 환경 (운영)

### 첫 셋업

```bash
cd terraform/environments/dev
terraform init
terraform plan
terraform apply
```

### EKS 접근

```bash
aws eks update-kubeconfig --name platform-cluster --region ap-northeast-2
kubectl get nodes
```

### Strimzi + Kafka 설치

```bash
# 1. Strimzi Operator
kubectl apply -f k8s/strimzi/

# 2. Kafka 클러스터 namespace 생성
kubectl create namespace platform-kafka

# 3. Kafka 클러스터
kubectl apply -f k8s/kafka/kafka-cluster.yaml

# 4. Kafka Connect (Debezium 포함 커스텀 이미지 사용)
# 먼저 docker/kafka-connect 빌드 후 ECR push
docker build -t platform-kafka-connect:latest docker/kafka-connect/
# ... push to ECR ...

kubectl apply -f k8s/kafka/kafka-connect.yaml
```

## 모니터링

```bash
kubectl apply -f k8s/monitoring/
```

Prometheus + Grafana + Loki 설치.
