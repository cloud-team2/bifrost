#!/usr/bin/env bash
set -euo pipefail

# EKS 클러스터 kubeconfig 업데이트
# 실행: ./scripts/setup-kubeconfig.sh

CLUSTER_NAME="skala3-cloud-team-Curly"
AWS_REGION="ap-northeast-2"
AWS_PROFILE="skala_student"

echo "EKS kubeconfig 업데이트 중..."
aws eks update-kubeconfig \
  --region "${AWS_REGION}" \
  --name "${CLUSTER_NAME}" \
  --profile "${AWS_PROFILE}"

echo ""
echo "현재 컨텍스트 확인:"
kubectl config current-context

echo ""
echo "노드 상태 확인:"
kubectl get nodes
