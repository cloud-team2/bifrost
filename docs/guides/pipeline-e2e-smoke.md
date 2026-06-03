# EDA/CDC 파이프라인 real E2E smoke (#32)

operations-backend의 real provisioner(`provisioning.mode=real`)가 실제 EKS 클러스터에서
EDA/CDC 파이프라인을 생성하고 KafkaConnector가 정상 동작하는지 판정하는 smoke 절차다.
자동화 스크립트는 [`scripts/pipeline-e2e-smoke.sh`](../../scripts/pipeline-e2e-smoke.sh).

## 전제 조건

| 항목 | 기대 상태 |
| --- | --- |
| Kafka 클러스터 `platform-kafka` | Ready (KRaft, scram listener :9094) |
| KafkaConnect `platform-connect` | `status.conditions[Ready]=True` (Debezium + JDBC Sink plugin 포함, #44) |
| operations-backend | `provisioning.mode=real`로 기동, 내부 API 접근 가능(port-forward 등) |
| KafkaUser/Secret | 대상 워크스페이스의 `proj-{slug}-user` 및 동명 Secret 존재(#47) |
| Source/Sink DB Secret | `secretRef`가 가리키는 K8s Secret 등록(SecretStore) |
| `connectors` 테이블 | V4 마이그레이션 적용(#43) |

operations-backend 포트포워드 예:

```bash
kubectl -n bifrost-system port-forward deploy/operations-backend 8080:8080
```

## 실행

```bash
BASE_URL=http://localhost:8080 \
PROJECT_KEY=smoke \
SRC_HOST=userdb-postgres.userdb.svc.cluster.local SRC_DB=shop \
SRC_SCHEMA=public SRC_TABLE=orders SRC_SECRET_REF=secret://smoke-src \
SINK_HOST=userdb-mariadb.userdb.svc.cluster.local SINK_DB=warehouse \
SINK_SECRET_REF=secret://smoke-sink \
./scripts/pipeline-e2e-smoke.sh all     # eda | cdc | all
```

스크립트는 종료 코드로 결과를 알린다: `0`=GREEN, `1`=RED, `2`=전제/도구 오류.

## green/red 판정 기준

상태→파이프라인 매핑과 임계값은 기능명세서 부록 B를 단일 출처로 따른다. 본 smoke는
생성 직후 수렴(connector RUNNING)과 토픽 생성까지를 GREEN 기준으로 본다.

| 시나리오 | 단계 | GREEN 조건 | RED 조건 |
| --- | --- | --- | --- |
| 공통 | 생성 요청 | `POST /internal/pipelines` → **202 Accepted** | 422(부분 실패, `stage`/`errorCode`) 또는 4xx/5xx |
| EDA(FAN_OUT) | source connector | `{pipelineId}-source` 가 `TIMEOUT_SEC`(기본 90s) 내 **RUNNING** | `FAILED` 또는 시간 초과(creating 고착) |
| EDA | topic | `cdc.table.{slug}.{db}.{schema}.{table}` 생성됨 | 미생성 |
| CDC(DIRECT) | source connector | `{pipelineId}-source` **RUNNING** | `FAILED`/시간 초과 |
| CDC | sink connector | `{pipelineId}-sink` **RUNNING** | `FAILED`/시간 초과 |
| CDC | topic | source 토픽 생성됨 | 미생성 |

> 설계상 `creating`은 RUNNING 전이까지 최대 30초 유지한다. 스크립트는 in-cluster 이미지
> 빌드/스케줄 여유를 포함해 기본 90초까지 폴링하며, 환경변수 `TIMEOUT_SEC`로 조정한다.

### 데이터 흐름(심화, 선택)

connector RUNNING + 토픽 생성으로 control-plane은 GREEN이다. 실제 row 전파(source insert →
topic → sink upsert)까지 검증하려면 다음을 수동으로 확인한다(DB 접근/자격증명 필요):

1. source DB 대상 테이블에 1건 insert.
2. `cdc.table.{slug}.{db}.{schema}.{table}` 토픽에 메시지 도달(`kafka-console-consumer`).
3. CDC면 sink DB 대상 테이블에 동일 키가 upsert됐는지 조회.

## RED 시 점검 포인트

| 증상 | 점검 |
| --- | --- |
| 202 아님(422) | 응답 `stage`/`errorCode`(SECRET/SOURCE_CONNECTOR/SINK_CONNECTOR, #15). secretRef 해석·connector apply 확인 |
| connector creating 고착 | `kubectl -n platform-kafka describe kafkaconnector {name}`; plugin(class) 존재·DB 접속·ACL(`cdc.table.{slug}.*`) 확인 |
| sink만 FAILED | JDBC Sink plugin/드라이버(#44), `insert.mode`/`pk.mode`, sink DB 권한 |
| topic 미생성 | source connector 로그, `topic.creation.*` 설정, ACL CREATE 권한 |
| 상태가 DB에 반영 안 됨 | watcher(real 모드) 동작, `connectors` 행 존재(#43/#46), 재구독 로그 |

## 관련 이슈

- #44 KafkaConnect build에 JDBC Sink + DB driver plugin
- #45 real pipeline 생성 호출 경로(`POST /internal/pipelines`)
- #46 watcher 재구독 + connector 상태 DB 반영
- #47 KafkaUser/SCRAM provisioning(ACL `cdc.table.{slug}.*`)
- #15 단계별 실패 코드(422 응답의 `stage`/`errorCode`)
