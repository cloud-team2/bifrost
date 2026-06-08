# Claude Coding Prompt — Bifrost B안 아키텍처 구현 지시서

## 0. 역할

너는 대기업 상용 서비스 수준의 백엔드/AI 에이전트 아키텍처를 설계하고 구현하는 시니어 엔지니어다.

나는 `Bifrost` 프로젝트를 개발하고 있다.  
Bifrost는 Kafka 기반 EDA/CDC 파이프라인을 생성하고, Topic / Connector / Consumer Group / Offset / Lag / DB readiness / Incident / AI RCA를 하나의 Control Plane에서 관리하는 서비스다.

이번 구현 방향은 아래 **B안 구조**로 확정한다.

```text
FastAPI Agent(@tool)
  → Spring Boot Execution Control Plane
  → Kafka / EKS / Prometheus / Cruise Control
```

중요한 점은 다음과 같다.

- FastAPI Agent는 실제 Kafka/EKS/Prometheus를 직접 제어하지 않는다.
- FastAPI Agent는 자연어 해석, RCA, 조치 계획 생성, Tool 선택만 담당한다.
- FastAPI의 `@tool`은 실제 실행기가 아니라 Spring Boot API 호출 wrapper다.
- Spring Boot가 실제 실행 주체다.
- Spring Boot는 권한 검증, project scope 검증, approvalId 검증, Audit Log, Action Timeline 저장까지 담당한다.
- 변경성 작업은 반드시 HITL 승인 후 실행한다.
- 조회성 작업은 자동 실행 가능하다.
- Secret, DB password, token, credential 원문은 LLM에 전달하지 않는다.

---

## 1. 최종 아키텍처

다음 구조를 기준으로 코드를 설계해라.

```text
[React Frontend]
  - TA Console
  - Pipeline / Database / Cluster / Alerts / Incident
  - AI Chat Panel
  - HITL Run Button
        ↓

[FastAPI Agent Server]
  - 자연어 해석
  - Supervisor Agent
  - IncidentAnalystAgent
  - OpsChatAgent
  - ActionRecommenderAgent
  - ActionExecutorAgent
  - @tool 정의
  - Spring Boot API Client
        ↓ HTTP

[Spring Boot Execution Control Plane]
  - Auth / Role / Project / Pipeline / Incident
  - Approval 검증
  - Project Scope 검증
  - Topic / Connector / Namespace 소유권 검증
  - Audit Log 저장
  - Action Timeline 저장
  - 실제 실행
        ↓

[Runtime]
  - EKS / Kubernetes API Server
  - Strimzi Kafka CR
  - Kafka Connect REST API
  - Kafka AdminClient
  - Prometheus HTTP API
  - Cruise Control / KafkaRebalance CR
```

---

## 2. 서버별 책임

### 2.1 FastAPI Agent Server 책임

FastAPI는 AI 판단 계층이다.

담당 범위:

```text
- 사용자 자연어 요청 수신
- intent 분류
- RCA 생성
- recommended action 생성
- tool 선택
- structured output 생성
- Spring Boot API 호출 wrapper tool 제공
- SSE streaming 응답 제공
```

FastAPI가 직접 하면 안 되는 것:

```text
- Kubernetes API Server 직접 호출 금지
- Kafka Admin API 직접 호출 금지
- Kafka Connect REST 직접 호출 금지
- Prometheus API 직접 호출 금지
- KafkaRebalance CR 직접 생성 금지
- Secret 원문 조회 금지
- 승인 없이 mutation 실행 금지
```

FastAPI의 `@tool`은 다음처럼 동작해야 한다.

```text
@tool 함수
  → Spring Boot Internal API 호출
  → Spring Boot가 검증 및 실행
  → 결과를 FastAPI가 받아서 요약
```

### 2.2 Spring Boot 책임

Spring Boot는 실행 컨트롤 플레인이다.

담당 범위:

```text
- 인증/인가
- projectId / namespace / cluster scope 검증
- topic / connector / consumer group 소유권 검증
- approvalId 검증
- tool allowlist 검증
- mutation risk 검증
- 실제 Kafka/EKS/Prometheus/Rebalance 실행
- audit log 저장
- action timeline 저장
- incident 저장
```

Spring Boot 내부 실행 기술:

```text
K8s Tool       → Fabric8 Kubernetes Client
Kafka Tool     → Kafka AdminClient + Kafka Connect REST Client
Metrics Tool   → Prometheus HTTP API Client
Rebalance Tool → Fabric8로 KafkaRebalance CR 생성/조회/approve patch
```

---

## 3. Tool 분류

Tool은 크게 Read Tool과 Mutation Tool로 나눈다.

### 3.1 Read Tool

Read Tool은 승인 없이 실행 가능하다.

```text
- kafka.list_connectors
- kafka.describe_connector
- kafka.list_topics
- kafka.describe_topic
- kafka.list_consumer_groups
- kafka.get_consumer_group_lag
- k8s.get_pod_status
- k8s.get_pod_logs
- k8s.list_events
- k8s.get_kafkaconnector_status
- metrics.get_broker_metrics
- metrics.get_connect_worker_metrics
- metrics.get_consumer_lag_metrics
- rebalance.get_rebalance_status
```

### 3.2 Mutation Tool

Mutation Tool은 반드시 approvalId가 필요하다.

```text
- kafka.restart_connector
- kafka.pause_connector
- kafka.resume_connector
- k8s.scale_deployment
- rebalance.create_rebalance_proposal
- rebalance.approve_rebalance
```

Mutation Tool 공통 원칙:

```text
- approvalId 없으면 차단
- projectId 없으면 차단
- namespace가 project scope 밖이면 차단
- connector/topic이 project 소유가 아니면 차단
- delete 계열 destructive action은 구현하지 않음
- pods/exec는 구현하지 않음
- secret 원문 반환 금지
```

---

## 4. FastAPI Agent 구현 요구사항

### 4.1 프로젝트 구조

다음 구조로 작성해라.

```text
bifrost-agent/
 ├─ app/
 │   ├─ main.py
 │   ├─ config.py
 │   ├─ agents/
 │   │   ├─ supervisor_agent.py
 │   │   ├─ incident_analyst_agent.py
 │   │   ├─ ops_chat_agent.py
 │   │   ├─ action_recommender_agent.py
 │   │   └─ action_executor_agent.py
 │   │
 │   ├─ tools/
 │   │   ├─ kafka_tools.py
 │   │   ├─ k8s_tools.py
 │   │   ├─ metrics_tools.py
 │   │   └─ rebalance_tools.py
 │   │
 │   ├─ clients/
 │   │   ├─ spring_client.py
 │   │   └─ llm_client.py
 │   │
 │   ├─ schemas/
 │   │   ├─ chat.py
 │   │   ├─ tool.py
 │   │   ├─ action.py
 │   │   └─ incident.py
 │   │
 │   └─ policy/
 │       └─ guard.py
 │
 ├─ requirements.txt
 └─ Dockerfile
```

### 4.2 FastAPI Endpoints

다음 API를 구현해라.

```http
POST /agent/chat
POST /agent/plan
POST /agent/execute
POST /analyze/incident
GET  /health
```

#### POST /agent/chat

용도:

```text
사용자 자연어 요청을 받아 적절한 tool을 호출하거나 답변한다.
```

예시 요청:

```json
{
  "projectId": "project-001",
  "userId": "user-001",
  "role": "TA",
  "message": "failed connector 목록 보여줘",
  "context": {
    "pipelineId": "pipeline-001",
    "incidentId": null
  }
}
```

예시 응답:

```json
{
  "type": "tool_result",
  "summary": "FAILED 상태의 connector 1개를 확인했습니다.",
  "toolCalls": [
    {
      "toolName": "kafka.list_connectors",
      "status": "success",
      "risk": "low"
    }
  ],
  "cards": [
    {
      "type": "connector_status",
      "title": "FAILED Connectors",
      "items": []
    }
  ]
}
```

#### POST /agent/plan

용도:

```text
Incident 또는 자연어 요청을 기반으로 조치 계획을 생성한다.
실행하지 않는다.
```

예시 응답:

```json
{
  "incidentId": "inc-001",
  "summary": "Consumer lag 증가 원인은 connector task 지연 가능성이 높습니다.",
  "recommendedActions": [
    {
      "actionId": "act-001",
      "toolName": "kafka.restart_connector",
      "risk": "medium",
      "requiresApproval": true,
      "target": {
        "connectorName": "audit-logger"
      },
      "reason": "connector task가 FAILED 상태입니다."
    }
  ]
}
```

#### POST /agent/execute

용도:

```text
사용자가 Run 버튼으로 승인한 action을 실행한다.
FastAPI가 직접 실행하지 않고 Spring Boot API를 호출한다.
SSE 또는 일반 JSON 응답을 지원한다.
```

요청:

```json
{
  "projectId": "project-001",
  "incidentId": "inc-001",
  "actionId": "act-001",
  "approvalId": "approval-001",
  "toolName": "kafka.restart_connector",
  "params": {
    "connectorName": "audit-logger"
  }
}
```

동작:

```text
1. ActionExecutorAgent가 toolName을 확인한다.
2. 해당 tool wrapper를 호출한다.
3. tool wrapper는 Spring Boot run API를 호출한다.
4. Spring Boot가 approvalId와 scope를 검증하고 실제 실행한다.
5. 실행 결과를 FastAPI가 받아 SSE 또는 JSON으로 반환한다.
```

---

## 5. FastAPI Tool 구현 방식

### 5.1 공통 Tool Context

모든 tool은 아래 context를 받아야 한다.

```python
class ToolContext(BaseModel):
    project_id: str
    user_id: str
    role: str
    request_id: str
    incident_id: str | None = None
    pipeline_id: str | None = None
```

### 5.2 공통 Tool Result

```python
class ToolResult(BaseModel):
    tool_name: str
    status: Literal["success", "blocked", "failed"]
    risk: Literal["low", "medium", "high"]
    requires_approval: bool
    summary: str
    data: dict[str, Any] | None = None
    error: str | None = None
```

### 5.3 Kafka Tool Wrapper 예시

FastAPI tool은 실제 Kafka를 호출하지 않는다.

```python
@tool
async def list_connectors(context: ToolContext) -> ToolResult:
    response = await spring_client.get(
        f"/internal/tools/projects/{context.project_id}/kafka/connectors",
        headers={
            "X-User-Id": context.user_id,
            "X-Role": context.role,
            "X-Request-Id": context.request_id,
        },
    )

    return ToolResult(
        tool_name="kafka.list_connectors",
        status="success",
        risk="low",
        requires_approval=False,
        summary="Connector 목록을 조회했습니다.",
        data=response.json(),
    )
```

### 5.4 Mutation Tool Wrapper 예시

```python
@tool
async def restart_connector(
    context: ToolContext,
    connector_name: str,
    approval_id: str | None = None,
) -> ToolResult:
    if approval_id is None:
        return ToolResult(
            tool_name="kafka.restart_connector",
            status="blocked",
            risk="medium",
            requires_approval=True,
            summary="Connector 재시작은 approvalId가 필요합니다.",
            data={
                "connectorName": connector_name
            },
        )

    response = await spring_client.post(
        f"/internal/tools/projects/{context.project_id}/kafka/connectors/{connector_name}/restart",
        json={
            "approvalId": approval_id,
            "incidentId": context.incident_id,
            "requestId": context.request_id,
        },
        headers={
            "X-User-Id": context.user_id,
            "X-Role": context.role,
            "X-Request-Id": context.request_id,
        },
    )

    return ToolResult(
        tool_name="kafka.restart_connector",
        status="success",
        risk="medium",
        requires_approval=True,
        summary="Connector 재시작 요청이 실행되었습니다.",
        data=response.json(),
    )
```

---

## 6. Spring Boot 구현 요구사항

### 6.1 Spring Boot 프로젝트 구조

```text
bifrost-backend/
 └─ src/main/java/com/bifrost/
     ├─ auth/
     ├─ project/
     ├─ pipeline/
     ├─ database/
     ├─ incident/
     ├─ approval/
     ├─ audit/
     ├─ timeline/
     ├─ tool/
     │   ├─ controller/
     │   │   └─ InternalToolController.java
     │   ├─ service/
     │   │   ├─ ToolExecutionService.java
     │   │   ├─ ToolPolicyService.java
     │   │   └─ ToolAuditService.java
     │   └─ dto/
     │
     ├─ kafka/
     │   ├─ KafkaAdminService.java
     │   ├─ KafkaConnectService.java
     │   ├─ KafkaConsumerGroupService.java
     │   └─ KafkaTopicService.java
     │
     ├─ kubernetes/
     │   ├─ Fabric8ClientConfig.java
     │   ├─ KubernetesPodService.java
     │   ├─ StrimziResourceService.java
     │   └─ KafkaRebalanceService.java
     │
     ├─ metrics/
     │   └─ PrometheusQueryService.java
     │
     └─ common/
```

### 6.2 Spring Boot Internal Tool API

FastAPI Agent가 호출할 Spring Boot API를 작성해라.

#### Kafka Tools

```http
GET  /internal/tools/projects/{projectId}/kafka/connectors
GET  /internal/tools/projects/{projectId}/kafka/connectors/{connectorName}
POST /internal/tools/projects/{projectId}/kafka/connectors/{connectorName}/restart
POST /internal/tools/projects/{projectId}/kafka/connectors/{connectorName}/pause
POST /internal/tools/projects/{projectId}/kafka/connectors/{connectorName}/resume

GET  /internal/tools/projects/{projectId}/kafka/topics
GET  /internal/tools/projects/{projectId}/kafka/topics/{topicName}
GET  /internal/tools/projects/{projectId}/kafka/consumer-groups
GET  /internal/tools/projects/{projectId}/kafka/consumer-groups/{groupId}/lag
```

#### K8s Tools

```http
GET /internal/tools/projects/{projectId}/k8s/pods
GET /internal/tools/projects/{projectId}/k8s/pods/{podName}
GET /internal/tools/projects/{projectId}/k8s/pods/{podName}/logs
GET /internal/tools/projects/{projectId}/k8s/events
GET /internal/tools/projects/{projectId}/k8s/strimzi/kafka
GET /internal/tools/projects/{projectId}/k8s/strimzi/kafkaconnect
GET /internal/tools/projects/{projectId}/k8s/strimzi/kafkaconnectors
GET /internal/tools/projects/{projectId}/k8s/strimzi/kafkaconnectors/{connectorName}
```

#### Metrics Tools

```http
GET /internal/tools/projects/{projectId}/metrics/brokers
GET /internal/tools/projects/{projectId}/metrics/connect-workers
GET /internal/tools/projects/{projectId}/metrics/consumer-lag
GET /internal/tools/projects/{projectId}/metrics/pvc
POST /internal/tools/projects/{projectId}/metrics/query
```

#### Rebalance Tools

```http
POST /internal/tools/projects/{projectId}/rebalances
GET  /internal/tools/projects/{projectId}/rebalances/{rebalanceName}
POST /internal/tools/projects/{projectId}/rebalances/{rebalanceName}/approve
POST /internal/tools/projects/{projectId}/rebalances/{rebalanceName}/refresh
```

---

## 7. Spring Boot 실행 정책

모든 Internal Tool API는 실행 전에 다음을 반드시 검증해야 한다.

```text
1. 요청자 userId가 projectId에 접근 가능한가?
2. role이 해당 action을 실행할 수 있는가?
3. projectId가 namespace와 매핑되어 있는가?
4. connector/topic/consumer group이 해당 project 소유인가?
5. mutation tool이면 approvalId가 존재하고 유효한가?
6. approval의 toolName과 실제 toolName이 일치하는가?
7. approval의 paramsHash와 실제 params hash가 일치하는가?
8. destructive action이 아닌가?
9. Secret 원문을 반환하지 않는가?
10. 실행 결과를 audit log와 action timeline에 기록했는가?
```

---

## 8. Spring Boot Tool 실행 상세

### 8.1 K8s Tool — Fabric8

Fabric8 Kubernetes Client를 사용한다.

필요 기능:

```text
- Pod 목록 조회
- Pod 상태 조회
- Pod 로그 tail 조회
- K8s Event 조회
- Kafka CR status 조회
- KafkaConnect CR status 조회
- KafkaConnector CR status 조회
- KafkaRebalance CR 생성/조회/patch
```

주의:

```text
- pods/exec 구현 금지
- secret 원문 조회 금지
- delete action 구현 금지
- namespace는 project scope에서 가져온 값만 사용
```

### 8.2 Kafka Tool — Kafka AdminClient + Kafka Connect REST

Kafka AdminClient:

```text
- listTopics
- describeTopics
- listConsumerGroups
- describeConsumerGroups
- listConsumerGroupOffsets
- listOffsets
- describeCluster
```

Kafka Connect REST:

```text
- GET /connectors
- GET /connectors/{name}/status
- POST /connectors/{name}/restart
- PUT /connectors/{name}/pause
- PUT /connectors/{name}/resume
```

### 8.3 Metrics Tool — Prometheus HTTP API

Prometheus API:

```text
- GET /api/v1/query
- GET /api/v1/query_range
```

PromQL은 Spring Boot에서 allowlist 방식으로 관리한다.

허용 예시:

```text
- broker CPU
- broker disk
- broker network
- connect worker JVM heap
- connect worker GC
- consumer group lag
- PVC usage
```

LLM 또는 사용자가 임의 PromQL을 직접 실행하게 하지 마라.  
`metrics.query` API도 내부적으로 allowlisted query key만 받도록 설계해라.

### 8.4 Rebalance Tool — KafkaRebalance CR

KafkaRebalance는 Spring Boot가 Fabric8로 생성/조회/patch한다.

흐름:

```text
1. create_rebalance_proposal
   → KafkaRebalance CR 생성

2. get_rebalance_status
   → KafkaRebalance status 조회

3. approve_rebalance
   → strimzi.io/rebalance="approve" annotation patch

4. refresh_rebalance
   → strimzi.io/rebalance="refresh" annotation patch
```

KafkaRebalance 생성 예시:

```yaml
apiVersion: kafka.strimzi.io/v1beta2
kind: KafkaRebalance
metadata:
  name: bifrost-rebalance-001
  namespace: kafka
  labels:
    strimzi.io/cluster: bifrost-kafka
    bifrost.io/project-id: project-001
spec: {}
```

---

## 9. Audit Log / Action Timeline

모든 tool call은 기록한다.

### 9.1 Tool Call Audit

저장 필드:

```text
- requestId
- projectId
- userId
- role
- toolName
- toolType: read | mutation
- risk
- requiresApproval
- approvalId
- paramsHash
- status
- startedAt
- finishedAt
- errorMessage
```

### 9.2 Action Timeline

Mutation 성공/실패 결과는 Incident 또는 Pipeline Timeline에 남긴다.

저장 필드:

```text
- actionId
- incidentId
- projectId
- toolName
- targetType
- targetName
- approvalId
- status
- message
- executedBy
- executedAt
```

---

## 10. Security Guardrails

반드시 다음 정책을 코드에 반영해라.

```text
- FastAPI Agent의 요청을 신뢰하지 말 것
- Spring Boot가 최종 권한과 scope를 재검증할 것
- LLM에게 Secret, DB password, token, credential 원문을 넘기지 말 것
- CDC 이벤트 payload 본문은 LLM 분석 범위에서 제외할 것
- pods/exec 금지
- delete 계열 destructive action 금지
- user prompt로 tool 권한이 변경되지 않게 할 것
- 승인 전 mutation 실행 0건을 테스트할 것
- 모든 mutation은 approvalId가 없으면 차단할 것
```

---

## 11. 구현 순서

아래 순서로 개발해라.

### Phase 1 — Spring Boot Execution API 골격

```text
1. InternalToolController 생성
2. ToolPolicyService 생성
3. ToolAuditService 생성
4. 공통 ToolRequest / ToolResponse DTO 생성
5. approvalId 검증 stub 작성
6. paramsHash 계산 유틸 작성
```

### Phase 2 — K8s Tool 구현

```text
1. Fabric8ClientConfig 작성
2. KubernetesPodService 작성
3. StrimziResourceService 작성
4. KafkaRebalanceService 작성
5. K8s read API 구현
```

### Phase 3 — Kafka Tool 구현

```text
1. KafkaAdminClientPool 작성
2. KafkaTopicService 작성
3. KafkaConsumerGroupService 작성
4. KafkaConnectService 작성
5. connector restart/pause/resume 구현
```

### Phase 4 — Metrics Tool 구현

```text
1. PrometheusQueryService 작성
2. query key allowlist 작성
3. broker/connect/lag metric API 구현
```

### Phase 5 — FastAPI Agent Tool Wrapper 구현

```text
1. SpringClient 작성
2. kafka_tools.py 작성
3. k8s_tools.py 작성
4. metrics_tools.py 작성
5. rebalance_tools.py 작성
6. /agent/chat 구현
7. /agent/plan 구현
8. /agent/execute 구현
```

### Phase 6 — E2E 테스트

```text
1. Connector 목록 조회
2. Failed connector 조회
3. Consumer group lag 조회
4. Broker metric 조회
5. Connector restart 요청 → approval 없으면 blocked
6. approvalId 포함 restart → Spring 실행
7. KafkaRebalance proposal 생성
8. approve annotation patch
9. Audit Log / Timeline 확인
```

---

## 12. 테스트 기준

테스트 코드를 작성할 때 다음 케이스를 포함해라.

```text
- read tool은 approval 없이 실행 가능
- mutation tool은 approval 없이 blocked
- 권한 없는 projectId 접근 blocked
- namespace mismatch blocked
- approval의 toolName 불일치 blocked
- approval의 paramsHash 불일치 blocked
- Secret 원문 반환 없음
- pods/exec API 없음
- delete action API 없음
- 성공한 mutation은 timeline 저장
- 실패한 mutation도 audit 저장
```

---

## 13. 최종 산출물

다음 산출물을 만들어라.

```text
1. Spring Boot Internal Tool API 코드
2. Spring Boot 실행 서비스 코드
3. FastAPI Agent tool wrapper 코드
4. DTO / Schema
5. Security guardrail 코드
6. Audit / Timeline 기록 코드
7. README
8. API 명세서
9. E2E 테스트 시나리오
```

---

## 14. Claude에게 요청하는 작업 방식

너는 코드를 작성할 때 다음 방식으로 진행해라.

```text
1. 먼저 전체 패키지 구조를 제안한다.
2. 그 다음 핵심 DTO와 공통 응답 포맷을 작성한다.
3. Spring Boot Internal Tool API부터 구현한다.
4. 그 다음 K8s/Fabric8, Kafka, Metrics, Rebalance 순서로 구현한다.
5. 마지막에 FastAPI Agent Tool Wrapper를 구현한다.
6. 각 코드에는 필요한 import, 예외 처리, validation을 포함한다.
7. 보안상 위험한 기능은 구현하지 않는다.
8. 임시 코드나 TODO만 남기지 말고, 실행 가능한 형태로 작성한다.
9. 단, 실제 credential 값은 절대 코드에 하드코딩하지 않는다.
10. 긴 코드는 파일 단위로 나눠서 제시한다.
```

---

## 15. 최종 목표

최종 목표는 다음이다.

```text
사용자가 AI Chat에서
"failed connector 목록 보여줘"
라고 입력하면:

FastAPI Agent
  → kafka.list_connectors tool wrapper
  → Spring Boot Internal API
  → Kafka Connect REST
  → 결과 반환
  → AI가 요약
  → React 카드 표시
```

```text
사용자가
"audit-logger connector 재시작해줘"
라고 입력하면:

FastAPI Agent
  → action draft 생성
  → React 승인 카드 표시
  → 사용자가 Run 클릭
  → FastAPI ActionExecutorAgent
  → Spring Boot restart connector API 호출
  → Spring Boot가 approvalId 검증
  → Kafka Connect REST로 restart 실행
  → Audit Log / Timeline 저장
  → 결과 반환
```

```text
사용자가
"파티션 쏠림이 있으면 리밸런싱 제안 만들어줘"
라고 입력하면:

FastAPI Agent
  → metrics 조회
  → topic/partition 정보 조회
  → action plan 생성
  → 사용자가 Run 승인
  → Spring Boot가 KafkaRebalance CR 생성
  → Cruise Control proposal 생성
  → KafkaRebalance status 반환
```

이 구조를 기준으로 코드를 작성해라.
