#!/usr/bin/env bash
set -euo pipefail

# 로컬 개발 환경 시작 (Kafka, MetaDB, 사용자 DB)

cd "$(dirname "$0")/.."

echo "🚀 Docker Compose로 의존성 띄우는 중..."
docker-compose up -d

echo ""
echo "⏳ Kafka 준비 대기 중..."
for i in {1..30}; do
    if docker-compose exec -T kafka kafka-topics.sh --bootstrap-server localhost:9092 --list >/dev/null 2>&1; then
        echo "✅ Kafka 준비 완료"
        break
    fi
    sleep 2
done

echo ""
echo "⏳ MetaDB 준비 대기 중..."
for i in {1..15}; do
    if docker-compose exec -T meta-db pg_isready -U platform >/dev/null 2>&1; then
        echo "✅ MetaDB 준비 완료"
        break
    fi
    sleep 2
done

echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "✨ 로컬 환경 준비 완료"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""
echo "MetaDB:        localhost:5433 (user: platform / pw: platform / db: metadb)"
echo "User Postgres: localhost:5434 (user: debezium / pw: debezium / db: testdb)"
echo "User MariaDB:  localhost:3307 (user: debezium / pw: debezium / db: testdb)"
echo "Kafka:         localhost:9094 (external)"
echo "Kafka Connect: http://localhost:8083"
echo "Kafka UI:      http://localhost:8090"
echo ""
echo "이제 서비스를 실행하세요:"
echo "  ./gradlew :services:core-service:bootRun"
echo "  ./gradlew :services:orchestrator-service:bootRun"
echo "  cd services/frontend && pnpm dev"
