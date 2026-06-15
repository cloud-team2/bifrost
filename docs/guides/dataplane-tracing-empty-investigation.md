# 데이터플레인 트레이싱 미수집 투입 조사 (#666)

파이프라인 Tracing 탭/`/trace`가 dataplane tracing ON + 데이터 흐름 정상에도 trace를 한 건도 잡지 못하는
(`spans=[]`, `traceId=null`, internal trace count 0) 원인을 코드 레벨로 좁히고, **라이브 검증 runbook**과
수정 옵션을 정리한다. 라이브(kubectl/Tempo) 확인이 선행돼야 정본 수정을 확정할 수 있다.

## 1. 데이터 경로 (현 구현 기준)

**수집(emit→export)**
- source 커넥터 config의 `transforms`에 Debezium tracing SMT(`io.debezium.transforms.tracing.ActivateTracingSpan`)를
  per-pipeline 토글로 추가한다 — [`SourceDebeziumConnectorMapper.java:41,54-66`](../../services/operations-backend/src/main/java/com/bifrost/ops/provisioning/impl/strimzi/SourceDebeziumConnectorMapper.java#L41).
- Connect 워커는 Strimzi `tracing: type: opentelemetry`로 OTel javaagent가 주입되고, OTLP를
  **Tempo로 직송**한다(`OTEL_EXPORTER_OTLP_ENDPOINT=http://tempo.monitoring:4318`) —
  [`infra/k8s/kafka/kafka-connect.yaml:17-18,56-77`](../../infra/k8s/kafka/kafka-connect.yaml#L56).
- **샘플러: `parentbased_traceidratio`, ARG `0.05`(5%)** — 워커 Kafka poll-loop auto-instrumentation의
  trace 폭주(Tempo OOM)를 막으려는 head 샘플링.

**조회(query)**
- `/trace` → [`TraceQuery.java:53-103`](../../services/operations-backend/src/main/java/com/bifrost/ops/monitoring/query/TraceQuery.java#L53) →
  [`TempoClient`](../../services/operations-backend/src/main/java/com/bifrost/ops/adapters/tempo/TempoClient.java) `/api/search` TraceQL.
- TraceQL: topic 있으면 `{ resource.service.name="platform-connect" && span.messaging.destination.name="<topic>" }`,
  없으면 `{ resource.service.name="platform-connect" }` (service 범위 폴백). 시간창 `LOOKBACK_SEC=3600`(1h).
- `tempo.enabled=false`거나 Tempo 도달 실패면 **stub**("Tempo 비활성화"/"Tempo 조회 실패")을 반환한다.

## 2. 근본원인 가설 (확신도 순)

### H1 — head 샘플링 5% + Collector 우회 (최유력)
- 워커 OTLP가 **OTel Collector를 거치지 않고 Tempo로 직송**한다(`:4318`). 즉 tail-sampling(#370, 에러/지연
  보존)이 dataplane 경로에는 **적용되지 않고**, head 샘플링 5%가 유일한 필터다.
- Debezium SMT 스팬은 변경 이벤트마다 만들어지는 **루트 스팬**(들어오는 parent trace context 없음)이라
  `parentbased_traceidratio`가 ratio(5%)로 판정한다.
- 재현 e2e는 `sourceRows=2, sinkRows=2` → 생성 스팬 ~2–4개. 5% 샘플이면 **기대 보존 스팬 ≈ 0.1–0.2개**,
  즉 0건 확률 ≈ 90%+. 60초/12회 폴링 내내 0건·internal count 0 증상과 정확히 일치한다.
- 핵심: 켜는 토글(SMT 추가)은 동작해도, **워커 5% head 샘플링이 저볼륨 파이프라인의 dataplane 스팬을
  거의 전부 드롭**한다. kafka-connect.yaml 주석의 "특정 파이프라인 정밀 추적은 별도 토글"이 가리키는
  '풀샘플 경로'가 실제로는 없다.

### H2 — TraceQL `messaging.destination.name` 속성 불일치 (조건부)
- `/trace`는 topic이 있으면 topic-scoped TraceQL(`span.messaging.destination.name="<topic>"`)을 쓰고,
  **topic이 있으면 service-only 폴백을 쓰지 않는다**([`TraceQuery.java:97-103`](../../services/operations-backend/src/main/java/com/bifrost/ops/monitoring/query/TraceQuery.java#L97)).
- Debezium `ActivateTracingSpan` 스팬이 OTel semantic attribute `messaging.destination.name`을 실제로
  세팅하지 않으면(라이브러리 구현에 따라 다름), 스팬이 Tempo에 있어도 topic-scoped 쿼리는 0건이 된다.
- H1과 독립적으로 성립 가능 — H1을 풀어도 H2가 남으면 여전히 0건일 수 있다.

### H3 — Tempo 비활성/도달 실패 (배제)
- 이 경우 stub("Tempo 비활성화"/"조회 실패")이 나온다. 증상은 `spans=[]` + "구간 내 trace 없음"(쿼리는
  돌았고 0건) → **Tempo는 활성·도달 가능**. 배제.

### H4 — 토글이 커넥터에 반영 안 됨 (낮음, 확인 필요)
- `setSourceTracing`가 기존 커넥터 config에 `tracing`을 추가하지만, 반영/재시작이 실제로 일어나는지는
  CR로 확인해야 한다(아래 runbook (a)).

## 3. 라이브 검증 runbook

> EKS context/namespace 등 공통값은 [`getting-started-infra.md`](./getting-started-infra.md) 참조.
> 아래 `NS_KAFKA`(예: `platform-kafka`), `CONNECTOR`(source 커넥터명), `TOPIC`(CDC 토픽명)을 채워 실행.

**(a) 커넥터 CR에 tracing SMT가 실제로 들어갔는가 → H4 구분**
```bash
kubectl -n "$NS_KAFKA" get kafkaconnector "$CONNECTOR" -o jsonpath='{.spec.config.transforms}{"\n"}'
# 기대: "...,tracing"  +  transforms.tracing.type=io.debezium.transforms.tracing.ActivateTracingSpan
kubectl -n "$NS_KAFKA" get kafkaconnector "$CONNECTOR" -o jsonpath='{.spec.config.transforms\.tracing\.type}{"\n"}'
```

**(b) Tempo에 platform-connect 스팬이 존재하는가 (토픽 필터 없이, 넓은 창) → H1 vs H2 구분**
```bash
kubectl -n monitoring port-forward svc/tempo 3200:3200 &
# service 범위만 (topic 무시) — 스팬 자체 존재 여부
curl -s "http://localhost:3200/api/search?q=%7B%20resource.service.name%3D%22platform-connect%22%20%7D&limit=20&start=$(date -u -d '-1 hour' +%s)&end=$(date -u +%s)" | jq '.traces | length'
```
- 0건 → **H1 유력**(스팬 자체가 수집 안 됨, 샘플링 드롭).
- ≥1건인데 `/trace`만 0건 → **H2 유력**(스팬은 있으나 topic-scoped 쿼리가 못 잡음). 이때 한 trace를
  열어 span attribute에 `messaging.destination.name`이 있는지 확인:
```bash
curl -s "http://localhost:3200/api/traces/<traceId>" | jq '.. | .attributes? // empty'
```

**(c) 샘플링을 일시 100%로 올려 H1 확정**
```bash
# 워커 OTEL_TRACES_SAMPLER_ARG=1.0(또는 SAMPLER=always_on)로 임시 변경 후, 데이터 더 흘리고 (b) 재확인.
# 스팬이 잡히면 H1(샘플링) 확정. (검증용 임시 변경 — Tempo 용량 영향 주의, 끝나면 원복.)
```

**(d) Connect 워커가 OTLP export를 실제로 하는가**
```bash
kubectl -n "$NS_KAFKA" logs -l strimzi.io/cluster=<connect-cluster> -c <connect-container> | grep -iE 'otel|otlp|exporter|tempo' | tail -30
```

## 4. 수정 옵션 (검증 후 택1·조합)

- **H1 확정 시**
  - (권장) Connect 워커 OTLP를 **OTel Collector 경유**로 변경하고(ops-backend `#370`과 동일 경로),
    head 샘플은 풀어주되(`always_on`) Collector **tail-sampling**이 poll-loop 플러드는 버리고 dataplane/에러
    스팬은 보존하게 한다. → `kafka-connect.yaml`의 `OTEL_EXPORTER_OTLP_ENDPOINT`를 Collector로,
    `OTEL_TRACES_SAMPLER`를 조정.
  - (대안) dataplane 전용 풀샘플 경로 도입 — Debezium SMT 스팬에 식별 attribute를 부여하고 Collector에서
    해당 attribute를 100% 보존. poll-loop만 head/tail로 억제.
  - (단순/위험) head 샘플 상향(0.05→상향). 가장 간단하나 poll-loop 폭주로 Tempo 용량 영향 → 용량 검토 필수.
- **H2 확정 시**: SMT가 실제 emit하는 attribute에 맞춰 `TraceQuery.traceqlFor`의 join 키를 교정하거나
  (`messaging.destination.name` 외 실제 키 사용), SMT config로 해당 attribute를 부여한다.
- **H4 확정 시**: 토글 시 커넥터 반영/재시작 보장(설정 변경 후 restart 트리거) 추가.

## 5. 결론

증상(stub 아님 + internal count 0)은 **수집 단계에서 스팬이 Tempo에 거의 들어오지 않음**을 가리키며,
코드 레벨 근거상 **H1(워커 head 샘플링 5% + Collector 우회로 저볼륨 dataplane 스팬 드롭)** 이 최유력이다.
H2(topic 속성 불일치)는 독립적으로 잔존 가능하므로 runbook (b)로 H1/H2를 분리 확인한 뒤 정본 수정을 적용한다.
