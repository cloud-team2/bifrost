"""Run 단위 재현성 manifest(#885, 설계문서 §7-4 / §5.2).

RCA run 을 나중에 똑같이 재현하려면 "당시 어떤 모델·프롬프트·카탈로그·코드로
판단했는지"가 run 마다 고정 저장돼야 한다. 이 모듈은 그 manifest 를 결정적으로
조립한다. 별칭 모델이 아니라 날짜 스냅샷 ID 를 쓰고, 프롬프트·증거 매트릭스·runbook·
corpus 는 내용 해시로 핀고정한다.
"""
from __future__ import annotations

import hashlib
import os
import subprocess
from functools import lru_cache
from pathlib import Path

from pydantic import BaseModel

from app.core.config import settings
from app.llm.model_router import agent_model_snapshot_map, model_snapshot_for_agent

# 사람이 관리하는 버전 라벨. 내용이 바뀌면 함께 올린다(내용 fingerprint 는 *_hash 가 담당).
PROMPT_VERSION = "0.1.0"
EVIDENCE_MATRIX_VERSION = "0.1.0"
RUNBOOK_VERSION = "0.1.0"

# 프롬프트 fingerprint 에 포함할 모듈(LLM agent 의 판단을 좌우하는 프롬프트 전체).
_PROMPT_MODULES = (
    "app.prompts.router",
    "app.prompts.planner",
    "app.prompts.classifier",
    "app.prompts.rca",
    "app.prompts.remediation",
    "app.prompts.verifier",
    "app.prompts.report",
    "app.prompts.domain",
)


class ReproducibilityManifest(BaseModel):
    """run record 에 남기는 최소 재현성 필드(설계문서 §5.2)."""

    model_id: str
    model_tier_map: dict[str, str]
    prompt_version: str
    prompt_hash: str
    catalog_version: str
    evidence_matrix_version: str
    runbook_version: str
    corpus_manifest_hash: str
    eval_dataset_version: str
    code_commit_sha: str
    temperature: float


def _hash_text(*parts: str) -> str:
    digest = hashlib.sha256()
    for part in parts:
        digest.update(part.encode("utf-8"))
        digest.update(b"\x00")
    return digest.hexdigest()[:16]


def _module_source(module_path: str) -> str:
    import importlib

    module = importlib.import_module(module_path)
    file = getattr(module, "__file__", None)
    if not file:
        return ""
    try:
        return Path(file).read_text(encoding="utf-8")
    except OSError:
        return ""


def _service_root() -> Path:
    import app

    # app/__init__.py → .../app → .../ai-service
    return Path(app.__file__).resolve().parent.parent


@lru_cache(maxsize=1)
def _prompt_hash() -> str:
    return _hash_text(*(_module_source(m) for m in _PROMPT_MODULES))


@lru_cache(maxsize=1)
def _evidence_matrix_hash() -> str:
    return _hash_text(_module_source("app.catalogs.evidence_matrix"))


@lru_cache(maxsize=1)
def _runbook_hash() -> str:
    return _hash_text(_module_source("app.catalogs.runbooks"))


@lru_cache(maxsize=1)
def _corpus_manifest_hash() -> str:
    path = _service_root() / "corpus" / "manifest.json"
    try:
        return _hash_text(path.read_text(encoding="utf-8"))
    except OSError:
        return "unknown"


@lru_cache(maxsize=1)
def _code_commit_sha() -> str:
    configured = (settings.code_commit_sha or os.getenv("CODE_COMMIT_SHA") or "").strip()
    if configured:
        return configured
    try:
        out = subprocess.run(
            ["git", "rev-parse", "--short", "HEAD"],
            cwd=str(_service_root()),
            capture_output=True,
            text=True,
            timeout=2,
        )
        sha = out.stdout.strip()
        if out.returncode == 0 and sha:
            return sha
    except Exception:
        pass
    return "unknown"


def build_reproducibility_manifest() -> ReproducibilityManifest:
    """현재 코드/설정 기준 재현성 manifest 를 조립한다(run 시작 시 1회 호출)."""
    return ReproducibilityManifest(
        # RCA 등 분석 판단의 기준 모델(날짜 스냅샷). 전체 tier 매핑은 model_tier_map 에.
        model_id=model_snapshot_for_agent("rca"),
        model_tier_map=agent_model_snapshot_map(),
        prompt_version=PROMPT_VERSION,
        prompt_hash=_prompt_hash(),
        catalog_version=settings.catalog_version,
        evidence_matrix_version=f"{EVIDENCE_MATRIX_VERSION}+{_evidence_matrix_hash()}",
        runbook_version=f"{RUNBOOK_VERSION}+{_runbook_hash()}",
        corpus_manifest_hash=_corpus_manifest_hash(),
        eval_dataset_version=settings.eval_dataset_version,
        code_commit_sha=_code_commit_sha(),
        temperature=settings.llm_temperature,
    )
