# knowledge_chunk Seed (RAG Corpus)

## Purpose

Seed selected operational docs into `agentdb.knowledge_chunk` so `simple_query`
can retrieve real RAG context.

## Operating Policy

- Reindex after relevant docs or catalog changes.
- `doc_version` is the current git short SHA.
- Default `scope` is `global` for shared docs.
- Project-specific corpora should use `scope=project:{id}` in a follow-up.
- Embedding model is `AI_EMBEDDING_MODEL` / `settings.embedding_model`.
- Embedding dimension is `settings.embedding_dimensions`; changing it requires a migration and reindex.

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

### EKS

Use a one-shot Kubernetes Job or `kubectl exec` into the AI service image with:

```bash
python scripts/knowledge_seed.py --docs-root docs --scope global
```

The preferred long-term path is a dedicated Job that runs after migrations.

## Verify

```sql
SELECT COUNT(*) FROM knowledge_chunk;

SELECT doc_id, doc_type, COUNT(*) AS chunks
  FROM knowledge_chunk
 GROUP BY doc_id, doc_type
 ORDER BY doc_id;
```

Runtime check:

1. `POST /api/v1/agent/runs` with `mode=simple_query` and a question such as `DLQ가 뭐야?`.
2. Check that RAG search returns `knowledge_chunk` context.
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
