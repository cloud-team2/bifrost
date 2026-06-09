# 모니터링 배포 가이드 — 파이프라인 상세 메트릭이 동작하려면 클러스터에 뭘 띄워야 하나

> 대상: 클러스터 배포 담당자. 파이프라인 상세 화면(처리량·전송 시간·이벤트 분포·consumer lag 등)이 **실데이터**로 뜨려면 아래 구성요소가 `platform-kafka` 네임스페이스에 있어야 한다.
>
> 핵심 그림: **Kafka/Connect 파드의 JMX → Prometheus가 스크랩 → 백엔드가 Prometheus에 PromQL 질의 → 프론트 차트**. 일부(행수·토픽·메시지)는 Prometheus 없이 백엔드가 DB/Kafka에 직접 접근한다.

## 1. 배포 구성요소 (순서대로)

| # | 구성요소 | 매니페스트 | 역할 |
|---|---|---|---|
| 1 | **JMX exporter 설정(broker)** | `infra/k8s/monitoring/01-kafka-metrics-configmap.yaml` | ConfigMap `kafka-metrics-config` — 브로커 JMX 룰 |
| 2 | **JMX exporter 설정(connect)** | `infra/k8s/monitoring/03-connect-metrics-configmap.yaml` | ConfigMap `connect-metrics-config` — Connect/Debezium JMX 룰 |
| 3 | **Kafka CR** | `infra/k8s/kafka/kafka-cluster.yaml` | `spec.kafka.metricsConfig` → `kafka-metrics-config` 참조 + **`spec.kafkaExporter: {}`**(consumer group lag) |
| 4 | **KafkaConnect CR** | `infra/k8s/connect/…`(EKS) / `infra/local/k8s/connect-kind.yaml`(로컬) | `spec.metricsConfig` → `connect-metrics-config` 참조 |
| 5 | **Prometheus + Service** | `infra/k8s/monitoring/02-prometheus.yaml` | Deployment + 스크랩 설정 + Service 3개 |
| 6 | **백엔드 환경변수** | ops-backend Deployment | `PROMETHEUS_ENABLED=true`, `PROMETHEUS_URL=http://prometheus.platform-kafka.svc:9090` |

> ⚠️ **순서 주의**: 1·2(ConfigMap)를 먼저 apply해야 3·4(CR)가 참조할 수 있다. CR을 나중에 수정하면 Strimzi가 파드를 롤링 재시작해야 JMX 룰이 반영된다.

`02-prometheus.yaml` 안에 포함된 것:
- Prometheus Deployment(`prom/prometheus`) + ConfigMap(스크랩 잡: `kafka-broker`, `kafka-exporter`, `kafka-connect`)
- Service `prometheus`(9090)
- Service `platform-kafka-kafka-exporter`(**9308 → targetPort 9404**) — Strimzi는 exporter Service를 자동 생성하지 않으므로 직접 필요
- Service `platform-connect-metrics`(9404) — Connect 파드 메트릭 스크랩 대상

## 2. 어떤 화면 항목이 어디서 오나 (의존성)

| 파이프라인 상세 항목 | 데이터 소스 | 필요 컴포넌트 |
|---|---|---|
| 처리량 추이(produce/consume) | broker JMX `messagesin/out`·exporter offset rate | JMX(broker) + kafka-exporter + Prometheus |
| 데이터 전송 시간(소스 지연) | Connect JMX Debezium `millisecondsbehindsource` | JMX(connect) + Prometheus |
| 이벤트 타입 분포 | Connect JMX Debezium `totalnumberof{create,update,delete}eventsseen` | JMX(connect) + Prometheus |
| consumer lag(메트릭 카드) | kafka-exporter `kafka_consumergroup_lag` | kafka-exporter + Prometheus |
| 동기화율 / 미동기화 Δ(행수) | **source/sink DB 직접 조회** | (Prometheus 불필요) |
| 토픽·파티션 정보 | **Kafka AdminClient** | (Prometheus 불필요, Kafka 접근만) |
| Messages 브라우저 | **Kafka Consumer** | (Prometheus 불필요) |

→ Prometheus/JMX가 없어도 **행수·토픽·메시지**는 동작하지만, **추이 차트·lag**는 비어 있게 된다(백엔드가 빈 목록/스냅샷으로 graceful degrade).

## 3. 백엔드 설정

```yaml
# ops-backend Deployment env
- name: PROMETHEUS_ENABLED
  value: "true"                                      # false면 Prometheus 경로 비활성(추이 차트 빈값)
- name: PROMETHEUS_URL
  value: "http://prometheus.platform-kafka.svc:9090" # 클러스터 내부 DNS
```

백엔드는 Kafka 접근(AdminClient·Consumer)도 필요하다 — bootstrap·SASL/SCRAM 설정은 기존 ops-backend 배포 기준을 따른다.

## 4. 배포 후 검증

```bash
# (a) Prometheus가 타깃을 정상 스크랩하는지
kubectl port-forward -n platform-kafka svc/prometheus 9090:9090
#   → http://localhost:9090/targets 에서 kafka-broker·kafka-exporter·kafka-connect UP 확인

# (b) 핵심 메트릭이 실제로 들어오는지(PromQL)
#   kafka_consumergroup_lag                      (kafka-exporter)
#   debezium_metrics_millisecondsbehindsource    (connect JMX)
#   kafka_server_brokertopicmetrics_messagesin_total  (broker JMX)

# (c) 백엔드 → Prometheus 연결
kubectl get deploy operations-backend -n platform-kafka -o yaml | grep -A1 PROMETHEUS
```

세 메트릭이 모두 비어 있으면: ① CR의 metricsConfig 누락, ② ConfigMap 미배포, ③ CR 변경 후 파드 미재시작, ④ Service 셀렉터/포트 매핑 중 하나를 의심한다.

## 5. (참고) Cluster 화면용은 별도

`infra/k8s/monitoring/04-node-exporter.yaml`·`05-cadvisor.yaml`와 JMX 룰 확장(controller·leader·record-rate)은 **Cluster 화면(#212/#213)** 전용이다. **파이프라인 상세 메트릭에는 불필요**하므로, 위 1~6만 있으면 파이프라인 상세는 동작한다.
