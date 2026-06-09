#!/usr/bin/env bash
# 소스 DB(testdb.public.orders)에 INSERT/UPDATE/DELETE를 지속 발생시켜 CDC 모니터링을 검증한다.
# Debezium이 변경을 잡아 토픽→sink로 흘리므로, 처리량 추이·데이터 전송 시간·이벤트 타입 분포·
# consumer lag·동기화율·메시지 브라우저가 실시간으로 움직이는지 눈으로 확인할 수 있다.
#
# 사용: infra/local/cdc-load-gen.sh        # 기본값으로 무한 루프(Ctrl-C로 종료)
#   INTERVAL=1 infra/local/cdc-load-gen.sh # 더 빠르게
#   PSQL은 호스트에 없어도 됨 — docker(postgres:16-alpine)로 매 사이클 실행한다.
set -uo pipefail

HOST=${SRC_HOST:-host.docker.internal}
PORT=${SRC_PORT:-5434}
DB=${SRC_DB:-testdb}
USER=${SRC_USER:-debezium}
PW=${SRC_PW:-debezium}
INTERVAL=${INTERVAL:-3}     # 사이클 간 대기(초)
INS=${INS:-3}              # 사이클당 insert 수
UPD=${UPD:-2}              # 사이클당 update 수
DEL=${DEL:-1}              # 사이클당 delete 수

echo "[cdc-load-gen] target=$USER@$HOST:$PORT/$DB orders, cycle: +${INS} insert / ~${UPD} update / -${DEL} delete, interval=${INTERVAL}s"
cycle=0
while true; do
  cycle=$((cycle + 1))
  docker run --rm -e PGPASSWORD="$PW" postgres:16-alpine \
    psql -h "$HOST" -p "$PORT" -U "$USER" -d "$DB" -q -v ON_ERROR_STOP=1 -c "
      insert into orders(customer, amount, status)
      select 'cust_'||floor(random()*1000)::int,
             round((random()*500)::numeric, 2),
             (array['pending','paid','shipped','cancelled'])[floor(random()*4+1)]
      from generate_series(1, ${INS});
      update orders set status = (array['paid','shipped','cancelled'])[floor(random()*3+1)],
                        amount = round((random()*500)::numeric, 2)
      where id in (select id from orders order by random() limit ${UPD});
      delete from orders where id in (select id from orders order by random() limit ${DEL});
    " >/dev/null 2>&1 \
    && echo "[$(date +%T)] cycle #$cycle ok (+${INS}/~${UPD}/-${DEL})" \
    || echo "[$(date +%T)] cycle #$cycle FAILED (DB 접속 확인)"
  sleep "$INTERVAL"
done
