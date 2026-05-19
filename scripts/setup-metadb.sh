#!/usr/bin/env bash
set -euo pipefail

# MetaDB (PostgreSQL on EBS) 설치
# 실행: ./scripts/setup-metadb.sh
# 전제 조건: kubectl 컨텍스트가 EKS로 설정, gp3 StorageClass 존재 (setup-strimzi.sh 이후)

MANIFEST_DIR="infra/k8s/metadb"

cd "$(dirname "$0")/.."

echo "[1/3] Namespace / Secret / PVC 생성 중..."
kubectl apply -f "${MANIFEST_DIR}/00-namespace.yaml"
kubectl apply -f "${MANIFEST_DIR}/01-secret.yaml"
kubectl apply -f "${MANIFEST_DIR}/02-pvc.yaml"

echo "[2/3] MetaDB Deployment / Service 생성 중..."
kubectl apply -f "${MANIFEST_DIR}/03-deployment.yaml"
kubectl apply -f "${MANIFEST_DIR}/04-service.yaml"

echo "[3/3] MetaDB 준비 대기 중..."
kubectl rollout status deployment/metadb -n metadb --timeout=120s

echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "MetaDB 설치 완료"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""
echo "클러스터 내부 접속 정보:"
echo "  Host: metadb-service.metadb.svc.cluster.local"
echo "  Port: 5432"
echo "  DB:   metadb"
echo "  User: platform"
echo ""
echo "⚠️  프로덕션 전에 반드시 01-secret.yaml 패스워드를 변경하세요."
