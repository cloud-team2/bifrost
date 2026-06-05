"""asyncpg connection pool lifecycle for the Agent Run Store (agentdb)."""
from __future__ import annotations

import asyncpg

_pool: asyncpg.Pool | None = None


async def init_pool(dsn: str) -> None:
    global _pool
    # strip sqlalchemy-style prefix; asyncpg uses plain postgresql://
    raw_dsn = dsn.replace("postgresql+asyncpg://", "postgresql://")
    _pool = await asyncpg.create_pool(raw_dsn)


async def close_pool() -> None:
    global _pool
    if _pool is not None:
        await _pool.close()
        _pool = None


def get_pool() -> asyncpg.Pool:
    if _pool is None:
        raise RuntimeError("Database pool is not initialised. Call init_pool() first.")
    return _pool
