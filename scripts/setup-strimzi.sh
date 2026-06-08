#!/usr/bin/env bash
set -euo pipefail

# Strimzi Operator + Kafka 클러스터(KRaft) + 내부 토픽 설치
# 실행: ./scripts/setup-strimzi.sh
# 전제 조건: kubectl 컨텍스트가 EKS로 설정되어 있어야 함

STRIMZI_CHART_VERSION="1.0.0"
KAFKA_NAMESPACE="platform-kafka"

cd "$(dirname "$0")/.."

# ─── 1. Strimzi Operator (Helm) ───
echo "[1/5] Strimzi Operator 설치 중..."
helm repo add strimzi https://strimzi.io/charts/ 2>/dev/null || true
helm repo update

helm upgrade --install strimzi-kafka-operator strimzi/strimzi-kafka-operator \
  --namespace strimzi-system \
  --create-namespace \
  --version "${STRIMZI_CHART_VERSION}" \
  --set watchNamespaces="{platform-kafka}" \
  --wait \
  --timeout 5m

echo "Strimzi Operator 준비 완료"

# ─── 2. StorageClass + Namespace ───
echo "[2/5] StorageClass / Namespace 생성 중..."
kubectl apply -f infra/k8s/kafka/00-storage-class.yaml
kubectl apply -f infra/k8s/kafka/00-namespace.yaml

# ─── 3. KafkaNodePool (KRaft) — Kafka CRD보다 먼저 적용해야 함 ───
echo "[3/5] KafkaNodePool 생성 중..."
kubectl apply -f infra/k8s/kafka/01-kafka-node-pools.yaml

# ─── 4. Kafka 클러스터 (KRaft 모드) ───
echo "[4/5] Kafka 클러스터(KRaft) 생성 중..."
kubectl apply -f infra/k8s/kafka/kafka-cluster.yaml

echo "Kafka 클러스터 Ready 대기 중 (최대 10분)..."
kubectl wait kafka/platform-kafka \
  --for=condition=Ready \
  --timeout=600s \
  -n "${KAFKA_NAMESPACE}"

echo "Kafka 클러스터 준비 완료"

# ─── 5. 내부 토픽 ───
echo "[5/5] 내부 토픽 생성 중..."
kubectl apply -f infra/k8s/kafka/03-kafka-topics.yaml

echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "Strimzi + Kafka(KRaft) 설치 완료"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""
echo "다음 단계:"
echo "  # DB(metadb·agentdb·tenantdb)는 GitOps로 배포됨 — ArgoCD databases/ 앱 (gitops 브랜치)"
echo "  make build-connect   # Kafka Connect 이미지 빌드 후 배포"
