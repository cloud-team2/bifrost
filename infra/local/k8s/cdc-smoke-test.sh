#!/usr/bin/env bash
# 로컬 real CDC 스모크 테스트(#99).
# kind+Strimzi+백엔드(real 모드)가 떠 있는 상태에서, source(Postgres)에 변경을 가하고
# sink(MariaDB)에 실제로 복제되는지 확인한다. 인프라를 만들지 않고 "이미 동작 중"인지만 검증.
#
# 사전 조건:
#   - kind 클러스터 "bifrost" + platform-kafka 네임스페이스(Kafka/Connect Ready)
#   - docker: user-postgres(5434), user-mariadb(3307)
#   - 백엔드: PROVISIONING_MODE=real 로 기동, orders-cdc 파이프라인 ACTIVE
set -euo pipefail

export KUBECONFIG="${KUBECONFIG:-/tmp/kind-bifrost.kubeconfig}"
NS=platform-kafka
PGC=user-postgres
MYC=user-mariadb
PSQL=(docker exec -i "$PGC" psql -U debezium -d testdb -t -A)
MYSQL=(docker exec -i "$MYC" mariadb -udebezium -pdebezium testdb -N)

echo "════════════════════════════════════════════════"
echo " 1) 인프라 상태"
echo "════════════════════════════════════════════════"
kubectl -n "$NS" get kafka,kafkaconnect --no-headers 2>/dev/null | sed 's/^/   /'
echo "   --- KafkaConnector (커넥터 + READY) ---"
kubectl -n "$NS" get kafkaconnectors --no-headers 2>/dev/null | awk '{print "   "$1" READY="$NF}'

echo
echo "════════════════════════════════════════════════"
echo " 2) 변경 전 행 수"
echo "════════════════════════════════════════════════"
SRC0=$("${PSQL[@]}" -c "SELECT count(*) FROM orders;")
SNK0=$("${MYSQL[@]}" -e "SELECT count(*) FROM orders;")
echo "   source(PG)=$SRC0   sink(Maria)=$SNK0"

echo
echo "════════════════════════════════════════════════"
echo " 3) source에 라이브 변경 (INSERT + UPDATE)"
echo "════════════════════════════════════════════════"
STAMP="smoke-$(date +%H%M%S)"
"${PSQL[@]}" -c "INSERT INTO orders(customer,amount,status) VALUES('$STAMP',42.00,'paid');" >/dev/null
"${PSQL[@]}" -c "UPDATE orders SET status='refunded' WHERE id=1;" >/dev/null
echo "   INSERT customer='$STAMP', UPDATE id=1 status='refunded' 완료"

echo
echo "════════════════════════════════════════════════"
echo " 4) sink 복제 대기 (최대 30초)"
echo "════════════════════════════════════════════════"
for i in $(seq 1 30); do
  GOT=$("${MYSQL[@]}" -e "SELECT count(*) FROM orders WHERE customer='$STAMP';")
  UPD=$("${MYSQL[@]}" -e "SELECT status FROM orders WHERE id=1;")
  if [ "$GOT" = "1" ] && [ "$UPD" = "refunded" ]; then
    echo "   ✅ ${i}초 만에 복제 확인 (INSERT + UPDATE 모두 반영)"
    break
  fi
  sleep 1
  [ "$i" = "30" ] && { echo "   ❌ 30초 내 미반영 — 커넥터 상태 점검 필요"; exit 1; }
done

echo
echo "════════════════════════════════════════════════"
echo " 5) sink(MariaDB) 최종 데이터"
echo "════════════════════════════════════════════════"
docker exec -i "$MYC" mariadb -udebezium -pdebezium testdb \
  -e "SELECT id,customer,amount,status,created_at FROM orders ORDER BY id;" | sed 's/^/   /'

echo
echo "✅ CDC 스모크 테스트 통과 — Postgres → Debezium → Kafka → JDBC sink → MariaDB"
