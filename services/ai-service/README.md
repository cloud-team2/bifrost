# ai-service (Bifrost AI Agent Server)

AI 장애대응 계층 (FastAPI). evidence 기반으로 Kafka 파이프라인 장애를 분석·대응 제안하며,
운영 리소스는 직접 만지지 않고 Spring Boot Operations Backend의 `/internal/ops`로 위임한다.

> 설계: `docs/design/backend-fastapi/` · API 표면: `docs/api/fastapi.md`

## 구조

```
services/ai-service/
├─ pyproject.toml          # 의존성 (uv 관리)
├─ Dockerfile
├─ .env                    # 로컬 환경변수 (gitignore — 커밋 금지)
├─ app/
│  ├─ main.py              # FastAPI 엔트리포인트
│  ├─ core/config.py       # Settings (env prefix AI_)
│  ├─ schemas/             # AgentState · StreamingEvent · Output 모델
│  ├─ api/                 # /api/v1 라우터 (health · agent runs · SSE)
│  ├─ agents/              # Router · Planner · Retrieval · Verifier · Report
│  ├─ workflow/            # runner.py · guards.py (루프 방지)
│  ├─ tools/               # Tool Client Registry → Spring /internal/ops
│  ├─ streaming/           # EventBus · SSE 포맷터 (재연결 지원)
│  ├─ persistence/         # Run · Event Repository (InMemory / PostgreSQL)
│  ├─ llm/                 # LLM provider (OpenAI, fallback 지원)
│  └─ supervisor/          # Supervisor 골격 (후속 이슈에서 확장)
└─ tests/
```

## 로컬 실행

```bash
cd services/ai-service

# 의존성 설치 (uv 사용)
uv sync

# 서버 기동
uv run uvicorn app.main:app --port 8082 --reload

# 확인
curl localhost:8082/api/v1/health
```

## 환경변수 (prefix `AI_`, `.env` 파일로 주입)

| 변수 | 기본값 | 설명 |
|---|---|---|
| `AI_PORT` | 8082 | 서버 포트 |
| `AI_SPRING_OPS_BASE_URL` | http://localhost:8080 | Spring `/internal/ops` 위임 대상 |
| `AI_LLM_API_KEY` | "" | OpenAI API 키 (없으면 fallback 텍스트 반환) |
| `AI_LLM_DEFAULT_MODEL` | gpt-4o-mini | 사용 모델 |
| `AI_DATABASE_URL` | postgresql+asyncpg://agent:agent@localhost:5432/agentdb | agentdb 연결 (없으면 InMemory fallback) |
| `AI_EMBEDDING_API_KEY` | "" | knowledge RAG 임베딩용 OpenAI 키 (없으면 `AI_LLM_API_KEY`→`OPENAI_API_KEY` fallback, 모두 없으면 HashingEmbedder) |
| `AI_EMBEDDING_MODEL` | text-embedding-3-small | 임베딩 모델 |
| `AI_EMBEDDING_DIMENSIONS` | 1536 | 임베딩 차원. **`alembic/versions/002`의 `vector(1536)`와 반드시 일치** (변경 시 새 마이그레이션 필요) |
| `AI_KNOWLEDGE_SEARCH_LIMIT` | 3 | simple_query RAG 검색 top-k |
| `AI_KNOWLEDGE_MIN_SCORE` | 0.05 | RAG 결과 최소 코사인 점수 |

`.env` 예시:
```env
AI_LLM_API_KEY=sk-...
AI_SPRING_OPS_BASE_URL=http://operations-backend:8080
```

## e2e 흐름 (단순 조회)

```
POST /api/v1/agent/runs
  → 즉시 응답: run_id + event_stream_url

GET /api/v1/agent/runs/{run_id}/events  (SSE)
  → run_started
  → agent_started/completed × 4  (Router → Planner → Retrieval → Verifier → Report)
  → tool_call_started / tool_call_completed
  → partial_result  (LLM 생성 답변)
  → run_completed

GET /api/v1/agent/runs/{run_id}
  → status: completed
```

Spring 미연결 시 tool mock 반환, LLM API 키 없을 때 fallback 텍스트 반환 (둘 다 정상 동작).

## 테스트

```bash
uv run pytest
```

## 인프라 인계

K8s 매니페스트 · Helm 차트 · agentdb 세팅은 `gitops` 브랜치(`charts/ai-service/`, `databases/agentdb/`) 참고.
