#!/usr/bin/env bash
set -euo pipefail

# Kafka Connect 커스텀 이미지 빌드 후 Docker Hub push
# 실행: ./scripts/build-push-connect.sh [TAG]
# 예:   ./scripts/build-push-connect.sh v1.0.0

DOCKERHUB_USER="hwnnn"
REPO="bifrost-kafka-connect"
TAG="${1:-latest}"
IMAGE="${DOCKERHUB_USER}/${REPO}:${TAG}"

cd "$(dirname "$0")/.."

# ─── Docker Hub 로그인 ───
echo "Docker Hub 로그인 중..."
docker login --username "${DOCKERHUB_USER}"

# ─── 이미지 빌드 ───
echo "이미지 빌드 중: ${IMAGE}"
docker build \
  --platform linux/amd64 \
  -f infra/docker/kafka-connect/Dockerfile \
  -t "${IMAGE}" \
  infra/docker/kafka-connect/

# ─── Docker Hub Push ───
echo "Docker Hub Push 중..."
docker push "${IMAGE}"

echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "빌드 완료: ${IMAGE}"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""
echo "배포:"
echo "  kubectl apply -f infra/k8s/kafka/kafka-connect.yaml"
