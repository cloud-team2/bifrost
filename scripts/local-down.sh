#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

echo "🛑 로컬 환경 종료 중..."

# --volumes를 붙이면 데이터까지 삭제. 기본은 컨테이너만 stop.
if [[ "${1:-}" == "--clean" ]]; then
    echo "⚠️  데이터까지 모두 삭제합니다."
    docker-compose down -v
else
    docker-compose down
    echo "ℹ️  데이터는 유지됩니다. 완전 삭제는: ./scripts/local-down.sh --clean"
fi

echo "✅ 종료 완료"
