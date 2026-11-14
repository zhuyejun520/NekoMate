from __future__ import annotations

from datetime import datetime, timezone
from typing import AsyncIterator

from sqlalchemy import DateTime, TypeDecorator
from sqlalchemy.ext.asyncio import AsyncEngine, AsyncSession, async_sessionmaker, create_async_engine
from sqlalchemy.orm import DeclarativeBase

UTC = timezone.utc


class UTCDateTime(TypeDecorator):
    """Store naive UTC in SQLite, always hand back timezone-aware UTC datetimes."""

    impl = DateTime
    cache_ok = True

    def process_bind_param(self, value: datetime | None, dialect):  # noqa: ANN001
        if value is None:
            return None
        if value.tzinfo is None:
            value = value.replace(tzinfo=UTC)
        return value.astimezone(UTC).replace(tzinfo=None)

    def process_result_value(self, value: datetime | None, dialect):  # noqa: ANN001
        if value is None:
            return None
        if value.tzinfo is None:
            return value.replace(tzinfo=UTC)
        return value.astimezone(UTC)


class Base(DeclarativeBase):
    pass


engine: AsyncEngine | None = None
_session_local: async_sessionmaker[AsyncSession] | None = None


class _LazySessionFactory:
    """Callable proxy so modules can ``from .database import SessionLocal`` early."""

    def __call__(self, *args, **kwargs) -> AsyncSession:
        if _session_local is None:
            raise RuntimeError("数据库尚未初始化")
        return _session_local(*args, **kwargs)

    def __repr__(self) -> str:  # pragma: no cover
        return f"<SessionFactory {'ready' if _session_local else 'pending'}>"


SessionLocal = _LazySessionFactory()


async def init_engine() -> None:
    global engine, _session_local
    from .config import settings

    engine = create_async_engine(
        settings.sqlalchemy_url,
        echo=False,
        pool_pre_ping=True,
        connect_args={"check_same_thread": False} if settings.sqlalchemy_url.startswith("sqlite") else {},
    )
    _session_local = async_sessionmaker(engine, expire_on_commit=False, class_=AsyncSession)


async def init_db() -> None:
    from . import models  # noqa: F401  (register metadata)

    assert engine is not None
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)
        await _migrate_sqlite(conn)


async def _migrate_sqlite(conn) -> None:
    """Tiny SQLite-only migrations for columns added after first release."""
    from sqlalchemy import text

    if conn.dialect.name != "sqlite":
        return
    try:
        rows = (await conn.execute(text("PRAGMA table_info(model_providers)"))).fetchall()
        columns = {row[1] for row in rows}
        if "owner_user_id" not in columns:
            await conn.execute(text("ALTER TABLE model_providers ADD COLUMN owner_user_id INTEGER"))
            await conn.execute(
                text("CREATE INDEX IF NOT EXISTS ix_model_providers_owner_user_id ON model_providers(owner_user_id)")
            )
    except Exception:
        pass


async def dispose_engine() -> None:
    global _session_local
    if engine is not None:
        await engine.dispose()
    _session_local = None


async def get_session() -> AsyncIterator[AsyncSession]:
    async with SessionLocal() as session:
        try:
            yield session
            await session.commit()
        except Exception:
            await session.rollback()
            raise
