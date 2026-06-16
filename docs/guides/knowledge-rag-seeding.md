# knowledge_chunk Seed (RAG Corpus)

## Purpose

Seed the full RAG corpus into `agentdb.knowledge_chunk` so `simple_query` and
`incident_analysis` can retrieve real context.

`scripts/knowledge_seed.py` is the single entrypoint. One run seeds **both**:

- The **built-in corpus** from code (`index_default_corpus()`): `glossary` +
  one `runbook` per `ROOT_CAUSE_RUNBOOKS` entry.
- The **docs allowlist** (`catalog` + `ops_doc`), listed at the bottom.

Pass `--no-builtin` to seed only the docs allowlist.

## Operating Policy

- Reindex after relevant docs or catalog changes.
- `doc_version` is the current git short SHA, so each deploy writes chunks under
  a new version. Use `--reset` to delete the scope's existing chunks before
  seeding, otherwise stale-version chunks accumulate.
- Default `scope` is `global` for shared docs.
- Project-specific corpora should use `scope=project:{id}` in a follow-up.
- Embedding model is `AI_EMBEDDING_MODEL` / `settings.embedding_model`.
- Embedding dimension is `settings.embedding_dimensions`; changing it requires a migration and reindex.
- **Index-time and query-time embedders must match.** Without
  `AI_EMBEDDING_API_KEY` / `AI_LLM_API_KEY` / `OPENAI_API_KEY` the service falls
  back to a deterministic hashing embedder — usable for tests but not semantic
  retrieval. Always inject a key in deployment.

## Run

### Local Dry Run

```bash
cd /path/to/bifrost
python scripts/knowledge_seed.py --docs-root docs --dry-run
```

From `services/ai-service`:

```bash
python ../../scripts/knowledge_seed.py --docs-root ../../docs --dry-run
```

### Local Indexing

```bash
cd /path/to/bifrost
export AI_DATABASE_URL="postgresql+asyncpg://agent:agent@localhost:5432/agentdb"
export AI_LLM_API_KEY="sk-..."
python scripts/knowledge_seed.py --docs-root docs
```

### EKS — dedicated Job after migrations (preferred)

The `alembic upgrade head` runs as an initContainer on the AI service Deployment,
so the table exists at rollout. Seeding the corpus is a separate concern: run it
as a one-shot Job (in the gitops repo) that uses the same image and reuses the
service env (DB URL + embedding key). Re-runs are idempotent within a version;
`--reset` clears stale-version chunks from prior deploys.

```yaml
apiVersion: batch/v1
kind: Job
metadata:
  name: knowledge-seed
spec:
  backoffLimit: 2
  template:
    spec:
      restartPolicy: Never
      containers:
        - name: knowledge-seed
          image: <ai-service-image>   # same image/tag as the Deployment
          command: ["python", "scripts/knowledge_seed.py", "--docs-root", "docs", "--scope", "global", "--reset"]
          envFrom:
            - secretRef:
                name: ai-service-secrets   # must include AI_DATABASE_URL and AI_EMBEDDING_API_KEY/AI_LLM_API_KEY
```

Ad-hoc alternative — `kubectl exec` into a running AI service pod:

```bash
python scripts/knowledge_seed.py --docs-root docs --scope global --reset
```

## Verify

```sql
SELECT COUNT(*) FROM knowledge_chunk;

SELECT doc_id, doc_type, COUNT(*) AS chunks
  FROM knowledge_chunk
 GROUP BY doc_id, doc_type
 ORDER BY doc_id;
```

`GET /api/v1/ready` reports corpus population directly: `dependencies.vector_store`
is `"empty"` when the table is reachable but unseeded (and `"ok"` once populated),
and `knowledge_corpus.chunks` carries the row count.

Runtime check:

1. `POST /api/v1/agent/runs` with `mode=simple_query` and a question such as `DLQ가 뭐야?`.
2. Check that RAG search returns `knowledge_chunk` context (`evidence_type=knowledge`).
3. Confirm the final report cites or reflects the seeded operational docs.

## Allowlist

- `docs/design/backend-fastapi/agent-principles.md`
- `docs/design/backend-fastapi/catalog/catalog-failure-types.md`
- `docs/design/backend-fastapi/catalog/catalog-root-causes.md`
- `docs/design/backend-fastapi/catalog/catalog-remediation-runbooks.md`
- `docs/design/backend-fastapi/catalog/catalog-evidence-matrix.md`
- `docs/design/backend-fastapi/catalog/catalog-policy-matrix.md`
- `docs/design/backend-fastapi/contract/contract-agent-roles.md`
- `docs/design/backend-springboot/monitoring.md`

## Limits

- No automatic reindex trigger yet.
- Project-level corpus isolation is a follow-up.
- Embedding cost is proportional to document size and chunk count.
- `incident_report` is a declared search doc_type but has **no source yet**.
  Retrieval lists it so resolved-run summaries can be indexed later (the intended
  dynamic-learning channel); until then it simply returns nothing.
