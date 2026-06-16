---
doc_id: catalog:catalog-correlation-rules
doc_type: catalog
title: Catalog — Correlation Rules (§10)
tags: [catalog, correlation, incident]
source: curated
---

# Catalog — Correlation Rules (§10)

> FastAPI Agent 카탈로그 · 개요 [overview](../overview.md) · 원리 [agent-principles](../agent-principles.md). **카탈로그**: [failure-types](./catalog-failure-types.md) · [incident→rootcause](./catalog-incident-root-cause-map.md) · [root-causes](./catalog-root-causes.md) · [evidence-matrix](./catalog-evidence-matrix.md) · [correlation-rules](./catalog-correlation-rules.md) · [runbooks](./catalog-remediation-runbooks.md) · [policy-matrix](./catalog-policy-matrix.md)

## 10. Catalog: Correlation Rules


### 1. 목적

Alert는 개별 이상 신호이고 Incident는 운영자가 대응하는 사건 단위다. 이 문서는 여러 Alert를 하나의 Incident 또는 Incident group으로 묶는 기준을 정의한다.

Correlation은 LLM 판단이 아니라 deterministic rule engine이 먼저 수행한다. Agent는 그 결과를 검토하고 필요한 경우 evidence를 추가한다.

### 2. 병합 후보 기준

Alert 병합 후보는 다음 네 축으로 판단한다.

| 축 | 설명 |
| --- | --- |
| time window | 비슷한 시간대에 시작했는가 |
| topology | 같은 pipeline, connector, topic, consumer group, dependency를 공유하는가 |
| shared change | 같은 배포, config, schema, credential 변경 이후 발생했는가 |
| symptom direction | upstream 문제가 downstream 증상을 만들 수 있는가 |

### 3. 병합 전략

| 전략 | 설명 | 사용 조건 |
| --- | --- | --- |
| rule-based immediate | alert 수신 즉시 규칙과 score로 병합 | alert 수가 적고 빠른 화면 반영 필요 |
| serial queue processing | alert를 queue에 넣고 순차 처리 | race condition과 중복 incident 방지 우선 |
| urgent plus window | 긴급 alert는 즉시 incident, 비긴급 alert는 window 내 병합 | 운영 영향 alert와 warning alert가 섞인 환경 |

v1 권장은 `urgent plus window`다. 고객 영향이 큰 alert는 즉시 Incident로 올리고, warning/secondary signal은 짧은 window 안에서 관련 신호로 병합한다.

### 4. Correlation Score

초기 score는 다음 항목을 사용한다. 가중치는 replay data로 보정한다.

| 항목 | 초기 weight | 설명 |
| --- | --- | --- |
| same pipeline | 0.25 | 같은 pipeline id |
| same dependency | 0.25 | 같은 source/sink/Kafka cluster |
| topology adjacency | 0.20 | upstream/downstream 관계 |
| same change | 0.20 | 같은 변경 이벤트 이후 발생 |
| time proximity | 0.10 | 시작 시간이 가까움 |

단, time proximity만으로 병합하지 않는다.

### 5. 병합 Decision

| Decision | 기준 |
| --- | --- |
| `merge_into_existing_incident` | dependency/topology/change 중 하나 이상과 time window가 맞음 |
| `create_new_incident` | 공통 근거가 없거나 다른 topology |
| `attach_as_related_signal` | 원인은 다를 수 있으나 같은 incident 분석에 참고 가치 있음 |
| `create_incident_group` | 여러 incident가 하나의 root cause를 공유할 가능성이 높음 |

### 6. Source 장애의 Downstream 증상

Source 장애는 downstream에서 여러 증상으로 나타날 수 있다.

예시:

```text
source timeout
  -> extract task failure
  -> Kafka topic ingress 감소
  -> downstream freshness delay
  -> sink write volume 감소
```

이 경우 downstream alert를 별도 root cause로 확정하기 전에 source evidence를 확인한다.

### 7. Sink 장애의 Upstream 영향

Sink 장애는 upstream에는 backlog나 lag로 나타날 수 있다.

예시:

```text
sink write timeout
  -> sink connector retry/backoff
  -> connector task failed
  -> consumer lag 증가
```

이 경우 consumer lag 자체를 root cause로 보지 않고 sink write evidence를 확인한다.

### 8. Incident Group

하나의 root cause가 여러 Incident를 만들 수 있다. 이때는 `incident_group`을 만든다.

사용 조건:

1. 서로 다른 pipeline에서 alert가 발생했다.
2. 같은 dependency, Kafka cluster, node, schema registry, 배포 이벤트 중 하나를 공유한다.
3. 증상 시작 시간이 같은 window 안에 있다.
4. 개별 incident별 증상이 공통 root cause에서 파생될 수 있다.

### 9. 병합 금지 조건

다음 경우에는 같은 시간대여도 병합하지 않는다.

- topology가 완전히 다름
- dependency가 다름
- root cause 후보가 서로 배타적임
- 하나는 customer-owned, 하나는 bifrost-owned로 증거가 명확히 갈림
- 단순 warning noise만 시간상 겹침

### 10. Output Schema

Correlation Engine은 다음 형식으로 결과를 남긴다.

```json
{
  "correlation_id": "corr_001",
  "decision": "create_incident_group",
  "incident_scope": "incident_group",
  "primary_alert_id": "alert_001",
  "related_alert_ids": ["alert_002", "alert_003"],
  "common_evidence": [
    {
      "type": "shared_dependency",
      "value": "source_db_users"
    }
  ],
  "confidence": 0.82
}
```

이 결과는 RCA 결론이 아니다. Classifier와 RCA가 추가 evidence로 검증한다.

---
