from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Callable


@dataclass
class StarMetadata:
    name: str = ""
    author: str = ""
    desc: str = ""
    version: str = "1.0.0"
    repo: str = ""
    display_name: str = ""
    short_desc: str = ""
    extra: dict[str, Any] = field(default_factory=dict)


def register(
    name: str | None = None,
    author: str | None = None,
    desc: str | None = None,
    version: str | None = None,
    repo: str | None = None,
    **kwargs: Any,
) -> Callable[[type], type]:
    """AstrBot 的插件注册装饰器。"""

    def decorator(cls: type) -> type:
        setattr(
            cls,
            "__astrbot_meta__",
            {
                "name": name or getattr(cls, "__name__", "plugin"),
                "author": author or "unknown",
                "desc": desc or "",
                "version": version or "1.0.0",
                "repo": repo or "",
                **kwargs,
            },
        )
        return cls

    return decorator


class Star:
    """AstrBot 插件基类。"""

    def __init__(self, context: "Context" | None = None, config: dict[str, Any] | None = None) -> None:
        self.context = context
        self.config = config or {}

    async def terminate(self) -> None:  # pragma: no cover - optional hook
        return None


class Context:
    """插件通过它访问宿主能力（配置、模型、发送消息等）。"""

    def __init__(self, bridge: Any = None) -> None:
        self._bridge = bridge

    # configuration ------------------------------------------------------
    def get_config(self, umo: str | None = None) -> dict[str, Any]:
        if self._bridge is None:
            return {}
        return self._bridge.get_plugin_config()

    def get_plugin_config(self, plugin_name: str | None = None) -> dict[str, Any]:
        return self.get_config()

    # providers ----------------------------------------------------------
    def get_using_provider(self, umo: str | None = None):
        if self._bridge is None:
            return None
        return self._bridge.get_provider()

    def get_provider_by_id(self, provider_id: str):
        return self.get_using_provider()

    # messaging ----------------------------------------------------------
    async def send_message(self, umo: str, message_chain: Any) -> None:
        if self._bridge is not None:
            await self._bridge.send_message(umo, message_chain)

    def register_web_api(self, route: str, view_handler: Any, methods: Any = None, desc: str = "") -> None:
        if self._bridge is not None:
            self._bridge.register_web_api(route, view_handler, methods, desc)

    def get_all_stars(self) -> list[Any]:
        return []

    def get_platform(self, platform_type: Any = None):
        return None

    def get_platform_name(self) -> str:
        return "nekocyte"

    async def text_to_speech(self, *args: Any, **kwargs: Any):
        return None


__all__ = ["Star", "Context", "register", "StarMetadata"]
