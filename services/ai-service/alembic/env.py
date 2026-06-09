from __future__ import annotations

from logging.config import fileConfig

from alembic import context
from sqlalchemy import engine_from_config, pool

from app.core.config import settings

config = context.config
if config.config_file_name is not None:
    fileConfig(config.config_file_name)


def _resolve_url() -> str:
    """마이그레이션 대상 DB URL 결정.

    런타임 설정(`settings.database_url` = env `AI_DATABASE_URL`)을 우선하고,
    비어 있으면 alembic.ini 의 `sqlalchemy.url` 로 폴백한다.
    동기 alembic 실행을 위해 asyncpg 드라이버 prefix 는 제거한다.
    """
    url = settings.database_url or config.get_main_option("sqlalchemy.url", "")
    return url.replace("postgresql+asyncpg://", "postgresql://")


def run_migrations_offline() -> None:
    context.configure(
        url=_resolve_url(), literal_binds=True, dialect_opts={"paramstyle": "named"}
    )
    with context.begin_transaction():
        context.run_migrations()


def run_migrations_online() -> None:
    configuration = {
        **config.get_section(config.config_ini_section, {}),
        "sqlalchemy.url": _resolve_url(),
    }
    connectable = engine_from_config(configuration, prefix="sqlalchemy.", poolclass=pool.NullPool)
    with connectable.connect() as connection:
        context.configure(connection=connection)
        with context.begin_transaction():
            context.run_migrations()


if context.is_offline_mode():
    run_migrations_offline()
else:
    run_migrations_online()
