# Spring Boot Operations Backend — 파이프라인·데이터베이스 라이프사이클 관리

> 이 문서는 **파이프라인과 데이터베이스의 라이프사이클(생성·상태 판정·삭제)과 실패 원인 attribution**을 한곳에 정리한 정본이다. 상태값 정의는 [부록 B.1~B.3](../../spec.md#부록-b--리소스-상태값-정의-및-자동-기준-단일-출처), 도메인 오케스트레이션은 [pipeline.md](./pipeline.md), CR 생성·watch는 [provisioning.md](./provisioning.md), 지표 산정은 [monitoring.md](./monitoring.md), DB 등록은 [database-registry.md](./database-registry.md)를 참조한다.
>
> 구현 이슈: 삭제 정책·고아 방지(#155), DB 헬스→파이프라인 전파(#179), 모니터링 정확도·Kafka 잔재 정리(#200).

## 1. 설계 원칙

1. **상태 쓰기의 단일 출처는 `PipelineStatusService`**. Watcher·폴링·헬스 프로브·사용자 조치가 동시에 들어와도 이 서비스의 `recompute`/`transition` 한 경로로만 파이프라인 상태가 바뀐다(일관성·감사·SSE 일원화).
2. **상태는 "등록 시점 값"이 아니라 "현재 실제 상태"를 반영해야 한다.** DB 연결은 주기적으로 프로브하고, connector 상태는 watch로 갱신한다.
3. **삭제는 잔재를 남기지 않는다.** KafkaConnector CR은 반드시 제거(고아 CR 0 보장), Kafka 토픽·consumer group도 정리한다.
4. **실패는 원인을 특정해 보여준다.** 파이프라인이 `error`면 "어디가" 원인인지(DB/connector) 메시지로 노출한다.

## 2. 파이프라인 상태 = f(connector, connect, cluster, db)

파이프라인이 정상인지 아닌지는 단일 신호가 아니라 **계층의 합성**으로 결정된다.

| 입력 | 출처 | 갱신 방식 |
| --- | --- | --- |
| **db** (source/sink 연결성) | `datasources.connection_status` | `DatabaseHealthProbeJob` 60초 주기 프로브(#179) |
| **connector** (Debezium/JDBC state·task) | `connectors.state` | `KafkaConnectorWatcher` Fabric8 watch |
| **connect** (KafkaConnect 클러스터 Ready) | KafkaConnect CR condition | (2단계 예정) |
| **cluster** (Kafka Ready) | Kafka CR condition | (2단계 예정) |

### 2.1 산정 규칙 (`PipelineStatusServiceImpl.recompute`)

```text
connectorNext = computeStatus(pattern, connectors)     # 부록 B.1/B.2
dbReason      = (current == creating) ? null : dbUnreachableReason(p)   # source/sink UNREACHABLE 사유
next    = (dbReason != null) ? error : connectorNext
message = (next == error)      ? (dbReason ?? firstConnectorError)
        : (next == lag)        ? firstConnectorError
        : null                                          # 정상이면 사유 클리어
```

- **DB 우선**: source/sink 중 하나라도 `UNREACHABLE`이면 connector가 `RUNNING`이어도 파이프라인은 `error`다. 이유: Debezium source는 source DB가 끊겨도 한동안 `RUNNING`을 유지하지만(트래픽이 없으면 실패를 늦게 감지) 파이프라인은 이미 비정상이기 때문(#179 검증: source DB kill → connector RUNNING인데 파이프라인 error).
- **creating 중에는 DB 사유를 보지 않는다**: 프로비저닝 진행 중 race를 피하기 위해.
- **connector 규칙**(`computeStatus`): 하나라도 `FAILED`→`error`, 일부 task만 실패(`PARTIALLY_FAILED`)→`lag`, `PAUSED`→`paused`, 기대 수(CDC 2·EDA 1)만큼 모두 `RUNNING`→`active`, 그 외→`creating`.
- **EDA(fan_out)**: sink consumer가 없으므로 lag을 보지 않고 source connector state로만 산정.

### 2.2 상태 머신

| from → to | 트리거 | 입력원 |
| --- | --- | --- |
| (없음) → `creating` | 생성 요청 | pipeline.service |
| `creating` → `active` | 기대 connector 모두 RUNNING | ConnectorWatcher |
| `creating` → `error` | connector FAILED / 프로비저닝 부분 실패 | Watcher · provisioning result |
| `creating` → `error` | **프로비저닝 타임아웃**(기본 N분 내 미전이) | `ProvisioningTimeoutJob`(60초 주기) → `failTimedOutCreating` |
| `active` ↔ `lag` | 일부 task FAILED(`PARTIALLY_FAILED`) | Watcher |
| `active`/`lag` → `error` | connector FAILED | Watcher |
| `active`/`lag`/`creating(아님)` → `error` | **source/sink DB UNREACHABLE** | `DatabaseHealthProbeJob` → `reevaluateForDatasource`(#179) |
| `error` → `active` | DB·connector 회복 | 프로브·Watcher 재계산 |
| `*` → `paused` / `paused` → `active` | 사용자 pause/resume | pipeline.service |
| `*` → (삭제) | 사용자 delete | pipeline.service (§4) |

> **creating이 멈추지 않게**: 과거엔 프로비저닝이 끝내 실패하면 `creating`에 영구히 머물러 삭제도 막혔다. 이제 `ProvisioningTimeoutJob`이 오래된 `creating`을 `error`로 내려, 상태 정확성과 삭제 가능성을 함께 보장한다(#155/#179).

## 3. 파이프라인 생성

```text
검증(pattern·ownership·CDC 준비도·단일 테이블·중복 이름)
  → metadb insert (status = creating)          # 단일 writer
  → provisioning.provision(command)            # Source[+Sink] KafkaConnector CR apply
  → 응답 {id, status: creating}                 # 즉시 반환(비동기 전이)
  → Watcher가 connector RUNNING 감지 → recompute → active + SSE
  → 타임아웃 내 미전이 → ProvisioningTimeoutJob이 error 전이
```

- **토픽 이름**: `cdc.table.{projectKey}.{dbName}.{schema}.{table}` — Debezium이 자동 생성. KafkaTopic CR을 따로 만들지 않는다.
- **재생성 주의**: 같은 (source, schema, table, pattern) 중복은 검증에서 차단된다. 다른 파이프라인을 같은 테이블로 만들면 토픽 이름이 같아질 수 있으므로, **삭제 시 토픽을 정리**해 누적을 막는다(§4·§6 참조).

## 4. 파이프라인 삭제 — 잔재 없는 정리

`DELETE /workspaces/{wsId}/pipelines/{id}?force={bool}`.

### 4.1 정책

| 상태 | normal 삭제 | force 삭제 |
| --- | --- | --- |
| `active`/`error`/`lag`/`paused` | 허용 | 허용 |
| `creating` | **차단**(in-flight race 방지) | 허용(상태 가드만 우회) |

- `creating` 차단이 막다른 길이 되지 않도록, 프로비저닝 실패는 §2.2 타임아웃으로 `error`가 되어 normal 삭제가 가능해진다. force는 그래도 안 잡히는 극단 상황의 안전판이다.

### 4.2 삭제 순서와 보장

```text
1) provisioning.delete(ref)                 # KafkaConnector CR 삭제 (Source[+Sink] + pid 접두사 sweep)
     └ 반드시 성공해야 함 — 실패 시 예외가 트랜잭션을 롤백 → 행이 남아 다음 시도에서 재정리
       ⇒ 고아 KafkaConnector CR이 절대 남지 않는다(#155)
2) kafkaResourceCleaner.deleteTopicAndSinkGroup(topic, pid)   # best-effort (#200)
     ① sink consumer group(connect-<pid>-sink)을 "비워질 때까지" 재시도하며 삭제
     ② 그 다음 토픽 삭제
3) connector 행 삭제 → pipeline 행 삭제 → event/audit
```

- **CR 정리는 강제(롤백 보장), Kafka 토픽·group 정리는 best-effort**(Kafka 일시 장애가 삭제 자체를 막지 않게). 토픽·group은 수동적 데이터라 잠시 남아도 위험하지 않다.
- **순서가 중요하다**(라이브 검증으로 확인, #200):
  - CR을 막 지운 직후엔 sink consumer가 아직 group에 남아 있어, group을 바로 지우면 `GroupNotEmptyException`. → consumer가 빠질 때까지 재시도(6회·1.2초).
  - consumer가 남은 채 토픽을 지우면, 빠져나가는 consumer의 메타데이터 요청이 `auto.create.topics.enable=true` 환경에서 **빈 토픽을 즉시 재생성**한다. → group을 먼저 비우고 **토픽은 맨 마지막**에 지운다.
- **삭제 정책 = 토픽·group 항상 삭제**(완전 정리). 단, EDA(fan-out) 토픽을 외부 컨슈머가 구독 중이면 그 컨슈머는 끊긴다(합의된 트레이드오프).

> **아직 남는 잔재(개선 대상)**: 삭제는 KafkaConnector CR·토픽·sink consumer group을 정리하지만, **PostgreSQL source의 publication·replication slot**은 아직 정리하지 않는다(`bif_{project}_{pid}_pub` 등이 누적). 후속 작업으로 source DB 측 정리를 추가한다.

## 5. 데이터베이스 라이프사이클

```text
등록(연결 테스트 → secretRef 보관 → CDC 준비도)         # database-registry.md
  → 주기 헬스 프로브(DatabaseHealthProbeJob, 60초)        # 연결 거부/timeout 즉시 감지
      connection_status: HEALTHY | UNREACHABLE
      connection_error, connection_checked_at
  → 상태 변화 시 reevaluateForDatasource(datasourceId)    # 이 DB를 쓰는 모든 파이프라인 재계산(#179)
      UNREACHABLE → 해당 파이프라인 error("source DB '<name>' 연결 불가: <error>")
      HEALTHY 회복 → connector 상태 기반으로 active 복귀
```

- DB 노드 색상(프론트): `UNREACHABLE` → error(빨강), 그 외 → connector 기반([mappers.ts `dbNodeStatus`]).
- 헬스는 **등록 시점 1회가 아니라 지속적으로** 갱신된다. DB 접속정보가 바뀌어 연결이 끊기면 사용자가 한눈에(파이프라인 error + DB 노드 빨강 + 사유) 알 수 있다.

## 6. 부록 — source와 sink의 row 수가 다른 이유

운영 중 source 테이블 행수와 sink 테이블/토픽 메시지 수가 어긋나 보이는 정상적인 경우들:

| 현상 | 원인 |
| --- | --- |
| sink rows < source rows | 초기 스냅샷 적재 중(아직 따라잡는 중) 또는 sink consumer lag |
| sink rows > source rows (과거) | 삭제 전파 지연/유실로 sink에 옛 행 잔존 — mirror(`delete.enabled`+tombstone, #175)로 해소 |
| **토픽 메시지 ≫ source rows** | 삭제된 파이프라인이 만든 토픽을 **재사용**해 재스냅샷 → 이벤트 누적(예: 1323행인데 토픽 2628). sink는 PK upsert라 최종 행수는 정상(1323)이지만 토픽엔 2배가 쌓임. → **삭제 시 토픽 정리**로 해소(§4, #200) |
| consumer lag이 0이 아닌데 동기화 100% | 삭제된 파이프라인의 **orphan consumer group**(`connect-<old-pid>-sink`)이 같은 토픽 lag에 합산. → 메트릭을 현재 파이프라인 group으로 필터 + 삭제 시 group 정리(#200) |

## 7. 로컬 검증 도구

- **CDC 부하 생성기**: [`infra/local/cdc-load-gen.sh`](../../../infra/local/cdc-load-gen.sh) — source `orders` 테이블에 INSERT/UPDATE/DELETE를 지속 발생시켜 처리량·전송 시간·이벤트 분포·lag·동기화율·메시지 브라우저가 실시간으로 움직이는지 확인한다.
- **장애 주입**: source/sink DB를 죽였다 살리며(`UNREACHABLE`→파이프라인 error→회복), connector를 죽이며 상태 전이·원인 메시지를 검증한다(#179).
