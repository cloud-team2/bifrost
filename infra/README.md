# Infra

플랫폼의 인프라 정의. Terraform(AWS EKS), Kubernetes 매니페스트, CI/CD bootstrap, Kafka Connect 커스텀 이미지 정의를 둔다. 애플리케이션 Helm chart와 GitOps Application은 `gitops` 브랜치가 배포 정본이다.

## 구성

```
infra/
├─ terraform/       AWS EKS 클러스터와 노드그룹 (기존 VPC/서브넷 사용)
├─ k8s/             Strimzi, Kafka, Connect, Monitoring, bootstrap ingress manifest
├─ cicd/            Jenkins/Argo CD/Harbor Helm values와 수동 bootstrap 스크립트
├─ local/           로컬·kind 검증용 Kafka/Connect/tenant DB 보조 파일
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

# 4. Kafka Connect (Debezium/JDBC/timestamptz converter 포함 Harbor 이미지 사용)
kubectl apply -f k8s/kafka/kafka-connect.yaml
```

Kafka Connect 이미지는 루트 `Jenkinsfile`이 `infra/docker/kafka-connect/` 또는 `connect-plugins/` 변경을 감지할 때 Kaniko로 빌드해 Harbor `harbor.harbor.svc.cluster.local/library/bifrost-kafka-connect:1.0.0-converter`에 push한다. 로컬 수동 빌드는 검증용이며 운영 배포 정본은 Jenkins build와 `infra/k8s/kafka/kafka-connect.yaml`의 `spec.image`다.

### CI/CD와 GitOps

`infra/cicd/deploy.sh`는 Jenkins, Argo CD, Harbor를 Helm으로 bootstrap하는 스크립트다. 현재 앱 배포 흐름은 `main` 전용 Jenkins job이 변경 서비스를 Kaniko로 빌드해 Harbor에 push하고, `gitops` 브랜치의 `charts/<svc>/values.yaml` tag를 갱신하면 Argo CD app-of-apps가 reconcile하는 구조다.

외부 노출의 현재 정본은 `gitops` 브랜치 `infra/` chart다. `harbor.skala-ai.com`, `jenkins.skala-ai.com`, `argocd.skala-ai.com`, `bifrost.skala-ai.com`은 단일 NLB → ingress-nginx → cert-manager(Let's Encrypt)로 TLS 종료한다. 이 브랜치의 `infra/k8s/ingress/*-ingress.yaml` ALB manifest는 bootstrap/이력용이며 현재 GitOps 노출 정본이 아니다.

## 모니터링

```bash
kubectl apply -f k8s/monitoring/
```

Prometheus + Grafana + Loki 설치. GitOps 운영 환경에서는 `gitops` 브랜치의 `4-observability-monitoring` Argo CD 앱이 kube-prometheus-stack, Loki, Tempo를 관리한다.
