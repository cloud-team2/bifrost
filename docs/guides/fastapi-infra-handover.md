# FastAPI ai-service 인프라 인계 리스트 → 정재환

> 이슈 #116. ai-service 구동에 필요한 인프라 항목을 정리한다.  
> 참고 설계: [server-design.md §9](../design/backend-fastapi/server-design.md#9-persistence-data-model), [§10 보안](../design/backend-fastapi/server-design.md#10-보안)

---

## 1. agentdb — Agent Run Store + Knowledge Vector Store

### 위치

| 항목 | 값 |
| --- | --- |
| K8s 네임스페이스 | `agentdb` |
| 매니페스트 경로 | `infra/k8s/agentdb/` |
| 이미지 | `pgvector/pgvector:pg16` (pgvector 확장 내장) |
| 서비스명 | `agentdb-service.agentdb.svc.cluster.local:5432` |
| 논리 DB | `agentdb` |
| 기본 계정 | `agent` / `agent` (Secret `agentdb-credentials`) |

### 구성 파일

```
infra/k8s/agentdb/
  00-namespace.yaml          # Namespace agentdb
  01-postgres-configmap.yaml # 초기화 SQL: CREATE EXTENSION IF NOT EXISTS vector
  02-postgres.yaml           # Secret · PVC(10Gi gp3) · Deployment · Service
```

### 적용 순서

```bash
kubectl apply -f infra/k8s/agentdb/00-namespace.yaml
kubectl apply -f infra/k8s/agentdb/01-postgres-configmap.yaml
kubectl apply -f infra/k8s/agentdb/02-postgres.yaml
```

### 리소스 요구량

| 항목 | requests | limits |
| --- | --- | --- |
| CPU | 100m | 1000m |
| Memory | 256Mi | 512Mi |
| Storage | — | 10Gi (PVC, gp3 RWO) |

### 마이그레이션

ai-service Pod 기동 후 아래 명령으로 스키마를 생성한다.

```bash
# ai-service Pod 안에서 실행 (또는 job으로 분리)
cd /app && alembic upgrade head
```

생성 테이블: `agent_run`, `state_patch`, `run_event`  
마이그레이션 파일: `services/ai-service/alembic/versions/001_create_agent_run_store.py`

---

## 2. pgvector — Knowledge Vector Store (RAG)

agentdb와 **같은 PostgreSQL 인스턴스에 co-locate**한다. 별도 컴포넌트 불필요.

| 항목 | 설명 |
| --- | --- |
| 확장 | `vector` (pgvector/pgvector:pg16 이미지에 포함) |
| 활성화 | `01-postgres-configmap.yaml`의 init SQL로 자동 실행 |
| 테이블 | `knowledge_chunk` (embedding 컬럼 `vector(N)`, hnsw 인덱스) |
| 스키마 생성 | 향후 alembic 마이그레이션으로 추가 (`002_create_knowledge_chunk.py`) |

> 코퍼스·스케일이 커지면 Qdrant/Milvus로 외부화한다. 인터페이스는 동일하게 유지.

---

## 3. LLM Provider 시크릿

ai-service는 LLM API 키를 K8s Secret으로 주입받는다. Secret은 **repo에 커밋하지 않으며** 클러스터에 직접 생성한다.

### Secret 생성

```bash
kubectl create secret generic ai-llm-secret \
  --namespace bifrost-system \
  --from-literal=api-key=<실제_LLM_API_KEY>
```

### Helm values 연동

`services/ai-service/helm/values.yaml`에서 provider와 모델을 설정한다.

```yaml
env:
  AI_LLM_PROVIDER: openai          # openai | anthropic | azure (추후 지원)
  AI_LLM_DEFAULT_MODEL: gpt-4o-mini
```

Deployment에서 Secret을 환경변수로 주입한다 (`optional: true`이므로 Secret 없으면 LLM 호출만 실패).

```yaml
- name: AI_LLM_API_KEY
  valueFrom:
    secretKeyRef:
      name: ai-llm-secret
      key: api-key
      optional: true
```

### 주의

- Secret은 `bifrost-system` 네임스페이스에 생성한다 (ai-service Pod과 동일 네임스페이스).
- `api-key` 키 이름을 그대로 유지한다 (Deployment 템플릿 하드코딩).
- Rotation 시 Pod 재기동 필요 (envFrom이 아닌 env 방식이므로 자동 반영 안 됨).

---

## 4. ai-service Helm 차트

### 위치

```
services/ai-service/helm/
  Chart.yaml        # name: ai-service, appVersion: 0.1.0
  values.yaml       # 기본값
  templates/
    deployment.yaml
    service.yaml
```

### 배포 네임스페이스

`bifrost-system`

### 주요 values

| 키 | 기본값 | 설명 |
| --- | --- | --- |
| `image.repository` | `harbor.harbor.svc.cluster.local/library/bifrost-ai-service` | Harbor 내부 주소 |
| `image.tag` | `latest` | 빌드 후 갱신 |
| `service.port` | `8082` | ClusterIP |
| `env.AI_SPRING_OPS_BASE_URL` | `http://operations-backend.bifrost-system.svc.cluster.local:8080` | Spring 위임 대상 |
| `env.AI_AGENTDB_DSN` | `postgresql://agent:agent@agentdb-service.agentdb.svc.cluster.local:5432/agentdb` | agentdb 연결 |
| `imagePullSecrets` | `harbor-push-secret` | Harbor pull 인증 |

### 배포 명령

```bash
# 최초
helm install ai-service services/ai-service/helm \
  --namespace bifrost-system \
  --create-namespace

# 갱신
helm upgrade ai-service services/ai-service/helm \
  --namespace bifrost-system
```

### Probe 경로

| 종류 | 경로 |
| --- | --- |
| liveness | `GET /health` |
| readiness | `GET /api/v1/ready` |

---

## 5. 리소스 요약 — 노드 용량 산정 참고

| 워크로드 | 네임스페이스 | CPU req | Mem req | 비고 |
| --- | --- | --- | --- | --- |
| **ai-service** | bifrost-system | 200m | 256Mi | Helm values.yaml |
| **agentdb** (PostgreSQL + pgvector) | agentdb | 100m | 256Mi | 02-postgres.yaml |

> 두 Pod 합산 기준: CPU **300m**, Memory **512Mi**.  
> 현재 클러스터(t3.xlarge 5노드)에서 CPU 요청 포화 우려가 있다([infra.md §11](../design/infra.md#11-클러스터-용량-분석-및-대응안-2026-06-02)).  
> ai-service + agentdb 추가 시 노드 여유 용량을 사전 확인하고 필요하면 노드 스펙업/추가를 진행한다.

---

## 6. 연관 이슈 / 문서

| 항목 | 링크 |
| --- | --- |
| Persistence 설계 | [server-design.md §9](../design/backend-fastapi/server-design.md#9-persistence-data-model) |
| 보안 정책 | [server-design.md §10](../design/backend-fastapi/server-design.md#10-보안) |
| 클러스터 용량 분석 | [infra.md §11](../design/infra.md#11-클러스터-용량-분석-및-대응안-2026-06-02) |
| 기존 인프라 가이드 | [getting-started-infra.md](./getting-started-infra.md) |
| ai-service K8s 매니페스트 | `infra/k8s/agentdb/` |
| ai-service Helm | `services/ai-service/helm/` |
