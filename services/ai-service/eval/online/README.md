# Live balanced RCA eval harness (#981)

배포된 RCA agent 에 실제 장애를 주입해 인시던트 → RCA 결과를 포착하고,
사전 정의 ground-truth(acceptable / confusion set) 대비 **AC@1 / AC@3 / AC@5 / Avg@5 / ECE**
를 채점하는 라이브 평가 하니스다. 채점은 오프라인과 동일한 모듈(`app.evaluation`)을 재사용한다.

오프라인(증거-only floor)은 `scripts/rca_eval_campaign.py`, gold set 기반 채점은
`app.evaluation.offline_eval` 이 담당한다. 이 하니스는 **라이브 주입** 축을 더한다.

```
eval/online/
  live_fault_specs.py        # fault 카탈로그(주입/복구 스텝, expected/confusion, 안전 등급)
  live_eval.py               # 러너 A(fault 주입): dry-run(기본) + 가드된 --live, 채점/리포트
  nl_tool_routing.py         # 러너 B(NL→tool 라우팅): dry-run(기본) + 가드된 --live, 채점/리포트
  test_live_eval.py          # live_eval dry-run/배선 단위 테스트(클러스터 무접촉)
  test_nl_tool_routing.py    # nl_tool_routing 채점/파싱 단위 테스트(네트워크 무접촉)
  README.md
eval/reports/                # 생성 리포트(JSON+markdown) 출력 위치(.gitignore 됨)
```

이 디렉터리는 라이브 평가의 **두 축**을 담는다:
- **A. fault 주입 → RCA 정확도** (`live_eval.py`): 실제 장애를 주입해 incident→RCA 를 포착하고
  AC@1/AC@3/AC@5/Avg@5/ECE 를 채점.
- **B. NL → tool 라우팅 효율** (`nl_tool_routing.py`): 자연어 채팅 질문이 *맞는* tool 로 *낭비 없이*
  라우팅되는지(routing@1, routing@hit, wasted_steps, latency) 채점.

## 채점 방식

- 각 fault 는 `expected_root_cause_ids`(**acceptable set**)를 가진다. RCA top-k 안에 이 집합의
  **어떤 id 든** 들어오면 hit 으로 인정한다. `expected_root_cause_ids[0]` 이 AC@1 의 기준 정답이다.
- `confusion_root_cause_ids` 는 RCA 가 헷갈릴 법한 후보로, 채점이 아니라 오답 분석에 쓴다.
- 채점기(`app.evaluation.metrics.EvalCase`)는 단일 정답만 받으므로, acceptable set 보존을 위해
  랭킹에서 가장 먼저 등장하는 acceptable id 를 primary 로 정규화한 뒤 AC@k / Avg@k 를 계산한다.
- ECE 는 `app.evaluation.calibration.compute_calibration` 으로 top-1 confidence vs 실제 정답률
  bin 격차를 가중 평균해 산출한다.

## 안전 등급

| 등급 | 의미 | --live 자동 주입 |
| --- | --- | --- |
| `auto`   | 가역적·테넌트 격리. 자동 주입/복구 가능 | 가능 |
| `manual` | 의도된 주입 방법은 명확하나 비결정적/수동 단계 필요 | **불가**(문서화만) |
| `unsafe` | prod 주입이 위험하거나 사실상 재현 불가 | **불가**(문서화만) |

### auto

- **sink_db_down** (`SINK_DB_CONNECTION_TIMEOUT`)
  `kubectl -n tenantdb scale deploy tenant-mariadb --replicas=0` → write 연결 timeout.
  복구: `replicas=1` + `rollout status` + sink connector REST restart.
- **source_db_down** (`SOURCE_DB_CONNECTION_TIMEOUT` | `SOURCE_NETWORK_REACHABILITY`)
  `kubectl -n tenantdb scale deploy tenant-postgres --replicas=0`.
  pod-down = host unreachable 이므로 두 root cause 가 acceptable set 으로 함께 인정된다.

> **selfHeal 주의**: ArgoCD app `3-data-tenantdb` 의 selfHeal 이 scale 변경을 ~40s 내 되돌린다.
> 주입 전에 selfHeal/prune 을 끄고(`automated.selfHeal=false,prune=false`), 복구 후 다시 켠다(`true`).
> 두 auto fault 의 inject/recover 스텝에 이 patch 가 포함돼 있다.

### manual (의도된 주입 + 사유)

- **connector_task_restart_storm** (`CONNECTOR_TASK_FAILED`): connector 반복 restart 는 가역적이나
  sink 쓰기 일관성을 흔들 수 있고 FAILED 가 비결정적. sink_db_down 결합이 더 결정적.
- **consumer_lag_spike** (`CONSUMER_LAG_SPIKE`): tenant-postgres `public.products` 대량 insert 로
  CDC 유입 급증. lag 임계 도달이 sink 처리량 의존 → 비결정적.
- **sink_constraint_violation** (`SINK_CONSTRAINT_VIOLATION`): upsert 모드라 대부분 흡수. 충돌을
  만들려면 sink 테이블 직접 조작 → 운영 데이터 위험.
- **config_change_regression** (`RECENT_CONFIG_CHANGE_REGRESSION`): 가역적이나 '변경 후 증상' 시간
  상관을 결정적으로 만들기 어렵고 connector 설정을 직접 변경.
- **pipeline_freshness_delay** (`PIPELINE_FRESHNESS_DELAY`): freshness 지연은 가역적이나 data_quality
  신호가 lag/latency 와 분리되어 표출되는지 비결정적.

### unsafe (의도된 주입 + 사유)

- **broker_resource_pressure** (`BROKER_RESOURCE_PRESSURE`): broker 는 모든 테넌트 공유 platform
  리소스 → blast radius 가 테넌트 격리를 벗어남.
- **schema_incompatible_change** (`SCHEMA_MISMATCH`): JDBC sink `auto.evolve` 가 대부분의 schema
  변경을 흡수 → 재현 불가 + sink 스키마 영구 변형 위험.
- **source_auth_revoke / sink_auth_revoke** (`SOURCE_AUTH_EXPIRED` / `SINK_AUTH_EXPIRED`): DB-admin
  자격으로 debezium 유저 권한/비밀번호 변조 필요 → CDC 영구 손상 위험.
- **connector_config_invalid** (`PIPELINE_CONFIG_INVALID`): Kafka Connect 가 config-set 시점에
  잘못된 설정을 거부(PUT 422) → 런타임 장애로 표출 안 됨 + 유효-해로운 설정은 connector 영구 손상 위험.
- **pod_oom_killed** (`POD_OOM_KILLED`): connect worker 가 모든 테넌트 파이프라인 호스팅 → OOM 이
  공용 영향. 격리 불가.

## 실행

### dry-run (기본, 클러스터 무접촉)

spec 무결성과 채점 파이프라인을 작은 합성 fixture `(expected, predicted_ranking, confidence)`
로 검증한다. AC@k / Avg@5 / ECE 가 실제로 계산됨을 증명한다. 클러스터/DB 를 절대 건드리지 않는다.

```bash
cd services/ai-service
.venv/bin/python -m eval.online.live_eval            # 기본이 dry-run
.venv/bin/python -m eval.online.live_eval --dry-run  # 명시
.venv/bin/python -m eval.online.live_eval --no-write  # 리포트 파일 미기록(콘솔만)
```

리포트는 `eval/reports/live_eval_dry-run_<UTC>.json` + `.md` 로 남는다(파일은 .gitignore).

### 단위 테스트

```bash
cd services/ai-service && .venv/bin/python -m pytest eval/online -q
```

### live (가드 — 실제 주입)

`auto` fault 만 실제 주입/폴링/복구한다. `--live` 와 `--confirm` 이 **둘 다** 있어야 동작한다
(이중 가드). 이 하니스는 **운영자 머신**에서 돌며, 클러스터 접근은 직접 DB 자격증명이 아니라
`kubectl exec` subprocess(psql/curl)로만 한다. DB 자격증명은 각 deployment 의 env
(`$POSTGRES_USER`/`$POSTGRES_DB`)에 이미 있어 컨테이너 안에서 그대로 참조한다 → 운영자 머신엔
**`kubectl` 컨텍스트만** 있으면 된다(별도 DSN 주입 불필요).

```bash
# kubectl 컨텍스트가 해당 클러스터를 가리키는 운영자 머신에서:
.venv/bin/python -m eval.online.live_eval --live --confirm --faults sink_db_down \
  --poll-timeout 300 --poll-interval 15
```

live 한 사이클(per auto fault):
1. **baseline 캡처**: incident resolve(게이트된 prod write)에 의존하지 **않는다**. 대신 주입 직전
   metadb 시계(`select now() at time zone 'utc'`)로 UTC baseline 을 찍고, 이후 `created_at > baseline`
   로 *신규* incident/RCA 만 본다. 같은 `grouping_key` 의 OPEN incident 가 이미 있으면(=dedup 으로
   새 incident 가 안 뜰 수 있음) **WARNING** 후 timestamp 기준으로 진행.
2. **inject**: spec inject 스텝(selfHeal disable + `scale replicas=0`) 실행. `sink_db_down` 은
   추가로 tenant-postgres 에 row 300건 insert 해 CDC 가 죽은 sink 로 write 시도→실패하게 트래픽 유발.
3. **poll**: timeout(기본 5분)까지 interval(기본 15s)로
   - incident: `metadb` `incidents`(tenant_id, created_at > baseline) 신규 1건.
   - RCA top-k: `agentdb` `report_snapshot` 의 최신 1행을 잡고
     `body->'root_cause_candidates'`(rank 순 jsonb 배열)을 `jsonb_array_elements … with ordinality`
     로 펼쳐 (root_cause_id, confidence) 랭킹을 만든다. *report_snapshot 은 run 당 1행*이라 컬럼의
     `limit 5` 는 5개 *run* 을 주므로 쓰지 않는다 — 랭킹은 body 안에 있다. body 가 비면 top-1 컬럼 폴백.
4. **recover (항상)**: `finally` 로 무조건 실행 → `scale replicas=1` + `rollout status` +
   Connect REST `POST /connectors/<name>/restart?includeTasks=true`(connect pod 동적 grep) +
   selfHeal re-enable. best-effort(한 스텝 실패가 나머지를 막지 않음)라 폴링 중 예외/크래시가 나도
   클러스터가 깨진 채 남지 않는다.
5. **score**: 포착된 관측을 `app.evaluation` 으로 채점 → `live_eval_live_<UTC>.json` + `.md`.

## B. NL → tool 라우팅 eval (`nl_tool_routing.py`)

자연어 채팅 질문이 (1) *맞는* tool 로, (2) *낭비 없이* 라우팅되는지 본다.

- **라벨 세트**: 한/영 혼합 18개(`ROUTING_CASES`). 각 case 는 `expected_tool`(1순위 정답) +
  `acceptable_tools`(대체 정답). tool 이름은 `app/tools/registry.py` 카탈로그와 일치(테스트로 검증).
- **채점**:
  - `routing@1`   : expected/acceptable tool 이 **첫** tool 호출이었는가(정확+효율).
  - `routing@hit` : trace 어디에서든 호출됐는가(정확성).
  - `wasted_steps`: 첫 정답 tool 앞에 끼어든 호출 수(미호출 시 전체 호출 수).
  - `latency_ms`  : run 생성→완료까지(라이브).
- **trace 관측**: agent 는 plan(planner)→실행(retrieval)하며 호출마다 SSE
  `tool_call_started`(payload `{"tool": …}`)를 낸다. 이게 event repo 에 적재돼
  `GET /api/v1/agent/runs/{run_id}/events/history`(plain JSON)로 시간순 조회된다.

### dry-run (기본, 네트워크 무접촉)

```bash
.venv/bin/python -m eval.online.nl_tool_routing            # 기본 dry-run
.venv/bin/python -m eval.online.nl_tool_routing --no-write # 콘솔만
```

### live (가드 — 배포 agent 실제 운전)

`--live` + `--confirm`(이중 가드). 배포 agent 를 demo 계정으로 직접 운전한다:
login → run 생성 → `events/history` 폴링 → tool trace 추출 → 채점.

```bash
BIFROST_BASE_URL=https://bifrost.skala-ai.com \
BIFROST_EMAIL=ta@bifrost.io BIFROST_PASSWORD=ta123456 \
.venv/bin/python -m eval.online.nl_tool_routing --live --confirm
```

- 로그인: `POST /api/v1/auth/login {email,password}` → `accessToken`(+`workspaceId`=project_id).
- 질의: `POST /api/v1/agent/runs {project_id, message, mode:"simple_query", stream:false}`
  (`Authorization: Bearer <token>`).
- env override: `BIFROST_BASE_URL` / `BIFROST_EMAIL` / `BIFROST_PASSWORD` / `BIFROST_PROJECT_ID`.

> 이 작업(#981) 범위에서는 live(주입·운전)를 **실행하지 않는다**. 빌드 + dry-run + 단위 테스트만 수행.
> 두 러너 모두 클러스터/네트워크 접근은 전부 `--live`(+`--confirm`) 뒤에 가드돼 있다.
