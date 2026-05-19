#!/usr/bin/env bash
set -euo pipefail

# CDC 소스/싱크 DB 시뮬레이션 설치 (PostgreSQL + MariaDB)
# 실행: ./scripts/setup-userdb.sh
# 전제 조건: gp3 StorageClass 존재 (setup-strimzi.sh 이후)

MANIFEST_DIR="infra/k8s/userdb"

cd "$(dirname "$0")/.."

echo "[1/3] Namespace 생성 중..."
kubectl apply -f "${MANIFEST_DIR}/00-namespace.yaml"

echo "[2/3] PostgreSQL (wal_level=logical) 설치 중..."
kubectl apply -f "${MANIFEST_DIR}/01-postgres-configmap.yaml"
kubectl apply -f "${MANIFEST_DIR}/02-postgres.yaml"

echo "[3/3] MariaDB (binlog ROW) 설치 중..."
kubectl apply -f "${MANIFEST_DIR}/03-mariadb-configmap.yaml"
kubectl apply -f "${MANIFEST_DIR}/04-mariadb.yaml"

echo "준비 대기 중..."
kubectl rollout status deployment/user-postgres -n userdb --timeout=120s
kubectl rollout status deployment/user-mariadb -n userdb --timeout=120s

echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "소스/싱크 DB 시뮬레이션 설치 완료"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""
echo "클러스터 내부 접속 정보:"
echo "  PostgreSQL: user-postgres-service.userdb.svc.cluster.local:5432"
echo "              user=debezium / pw=debezium / db=testdb"
echo "  MariaDB:    user-mariadb-service.userdb.svc.cluster.local:3306"
echo "              user=debezium / pw=debezium / db=testdb"
