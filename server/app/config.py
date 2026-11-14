from __future__ import annotations

import os
import secrets
from pathlib import Path
from typing import List

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_prefix="NM_", env_file=".env", env_file_encoding="utf-8", extra="ignore", case_sensitive=False
    )

    # ------- identity -------
    app_name: str = "NekoMate"
    app_title: str = "夜音 · AI 伴侣"
    version: str = "1.9.32"
    host: str = "0.0.0.0"
    port: int = 8080
    public_base_url: str = "http://127.0.0.1:8080"

    # ------- storage -------
    data_dir: Path = Path("./data")
    database_url: str = ""
    max_upload_mb: int = 128

    # ------- security -------
    secret_key: str = ""
    token_ttl_hours: int = 24 * 30
    admin_username: str = "admin"
    admin_password: str = "change-me-now"
    registration_enabled: bool = True
    cors_origins: str = "*"

    # ------- ai runtime defaults -------
    default_idle_minutes: int = 30
    default_proactive_min_minutes: int = 60
    default_proactive_max_minutes: int = 180
    default_proactive_max_streak: int = 3
    default_short_memory_days: int = 2
    max_context_messages: int = 24
    ai_request_timeout: int = 120
    stt_model: str = "whisper-1"

    # ------- misc -------
    log_level: str = "INFO"
    auto_create_ai_friend: bool = True

    # ------- derived -------
    @property
    def data_path(self) -> Path:
        return Path(self.data_dir).expanduser().resolve()

    @property
    def uploads_dir(self) -> Path:
        return self.data_path / "uploads"

    @property
    def plugins_dir(self) -> Path:
        return self.data_path / "plugins"

    @property
    def knowledge_dir(self) -> Path:
        return self.data_path / "knowledge"

    @property
    def static_dir(self) -> Path:
        return Path(__file__).parent / "static"

    @property
    def builtin_plugins_dir(self) -> Path:
        return Path(__file__).parent / "builtin_plugins"

    @property
    def sqlalchemy_url(self) -> str:
        if self.database_url:
            return self.database_url
        return f"sqlite+aiosqlite:///{(self.data_path / 'nekomate.db').as_posix()}"

    @property
    def cors_origin_list(self) -> List[str]:
        raw = (self.cors_origins or "*").strip()
        if raw == "*":
            return ["*"]
        return [x.strip() for x in raw.split(",") if x.strip()]

    def ensure_dirs(self) -> None:
        for p in (self.data_path, self.uploads_dir, self.plugins_dir, self.knowledge_dir):
            p.mkdir(parents=True, exist_ok=True)
        key_file = self.data_path / "secret.key"
        if not self.secret_key:
            if key_file.exists():
                self.secret_key = key_file.read_text(encoding="utf-8").strip()
            else:
                self.secret_key = secrets.token_urlsafe(48)
                key_file.write_text(self.secret_key, encoding="utf-8")


settings = Settings()
# allow `NM_DATA_DIR` style env even when pydantic already resolved defaults
if os.getenv("NM_DATA_DIR"):
    settings.data_dir = Path(os.getenv("NM_DATA_DIR"))  # type: ignore[assignment]
