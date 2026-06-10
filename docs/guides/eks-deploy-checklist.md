# EKS 배포 후 ai-service 검증 체크리스트

## 목적

ai-service 새 배포 후 #143 의 "(배포 후) 4건" + 일반 정합성 점검.

## 사전 준비

- kubectl + KUBECONFIG (EKS 클러스터 접근 권한)
- curl + jq

## 자동 검증

```bash
export KUBECONFIG=~/.kube/config-skala_student
export AI_SERVICE_HOST=https://ai-service.bifrost.skala-ai.com

bash scripts/verify-eks-deploy.sh
```

성공 시 "🎉 모든 검증 통과 — #143 (배포 후) 4건 close 가능" 출력.

## 수동 체크리스트 (스크립트 실패 시 디버깅용)

### 1. alembic 마이그레이션

```bash
kubectl exec -n agentdb $(kubectl get pod -n agentdb -l app=agentdb -o jsonpath='{.items[0].metadata.name}') \
  -- psql -U agent -d agentdb -c "SELECT * FROM alembic_version;"
# 기대: version_num = '005' (또는 head 진행 후 그 이상)
```

기대 테이블: agent_run · state_patch · run_event · knowledge_chunk · report_snapshot · change_ticket · run_feedback(006 머지 시)

### 2. ai-service 로그

```bash
kubectl logs -n bifrost-system $(kubectl get pod -n bifrost-system -l app=ai-service -o jsonpath='{.items[0].metadata.name}') | head -30
# 확인: "agentdb unavailable" 없음
```

### 3. /ready

```bash
curl -sS https://ai-service.bifrost.skala-ai.com/api/v1/ready | jq '.data.dependencies'
# 기대: agent_run_store=ok, spring_operations=ok|unavailable, llm_provider=ok|unavailable, vector_store=ok|unavailable, evidence_store=ok|unavailable
```

### 4. run 영속화 e2e

1. POST /runs 로 simple_query run 생성 → run_id 수령
2. SSE 구독 → run_completed 발행 확인
3. `kubectl rollout restart deployment/ai-service` + `rollout status`
4. 재시작 후 `GET /runs/{run_id}` → status = "completed" (재시작 전 결과 보존)

## 실패 시 디버깅

### alembic upgrade 실패

- initContainer 로그: `kubectl logs <ai-service-pod> -c migrate`
- alembic versions/ 디렉토리에 head 충돌 (multiple revisions) 확인
- 메모리 [[reference-agentdb-ops]] 참고

### agent_run_store=unavailable

- AI_DATABASE_URL secret 확인 (`AI_DATABASE_URL`, ⚠️ `AI_AGENTDB_DSN` 아님)
- agentdb Pod 가용성: `kubectl exec ... -- pg_isready -U agent`

### 재조회 시 404

- get_run_repo() 가 여전히 InMemory fallback 인지 확인 (_pool 상태)
- 로그에 "agentdb unavailable" 있으면 위 §2 확인

## 참고

- PR #283 (#143 본 PR)
- 메모리 [[reference-agentdb-ops]] (env 이름·initContainer 자동화·alembic head)
- `docs/guides/fastapi-infra-handover.md` (인프라 인계 노트)
