from __future__ import annotations

import re
from enum import Enum
from typing import Any, Callable, Iterable


class EventMessageType(Enum):
    ALL = "all"
    GROUP_MESSAGE = "group"
    PRIVATE_MESSAGE = "private"
    OTHER_MESSAGE = "other"


class PermissionType(Enum):
    ADMIN = "admin"
    MEMBER = "member"
    ALL = "all"


def _attach(func: Callable, kind: str, **meta: Any) -> Callable:
    handlers = list(getattr(func, "__astrbot_handlers__", []))
    handlers.append({"kind": kind, **meta})
    setattr(func, "__astrbot_handlers__", handlers)
    return func


def _names(value: Any) -> list[str]:
    if value is None:
        return []
    if isinstance(value, str):
        items = [value]
    elif isinstance(value, (list, tuple, set)):
        items = list(value)
    else:
        items = [str(value)]
    return [str(x).strip().lstrip("/!#") for x in items if str(x).strip()]


class _Filter:
    """Minimal stand-in for ``astrbot.api.event.filter``."""

    EventMessageType = EventMessageType
    PermissionType = PermissionType

    def command(self, command_name: Any = None, alias: Any = None, priority: int = 0, **kwargs: Any):
        names = _names(command_name) + _names(alias)

        def decorator(func: Callable) -> Callable:
            return _attach(func, "command", names=names, priority=priority)

        return decorator

    def command_group(self, group_name: Any = None, alias: Any = None, priority: int = 0, **kwargs: Any):
        names = _names(group_name) + _names(alias)

        def decorator(func: Callable) -> Callable:
            return _attach(func, "command_group", names=names, priority=priority)

        return decorator

    def regex(self, pattern: str = "", priority: int = 0, **kwargs: Any):
        def decorator(func: Callable) -> Callable:
            return _attach(func, "regex", pattern=pattern, priority=priority)

        return decorator

    def event_message_type(self, *types: Any, **kwargs: Any):
        normalized: list[str] = []
        for t in types:
            if isinstance(t, EventMessageType):
                normalized.append(t.value)
            elif isinstance(t, str):
                normalized.append(t)
            elif isinstance(t, Iterable):
                normalized.extend(str(getattr(x, "value", x)) for x in t)
        if not normalized:
            normalized = ["all"]

        def decorator(func: Callable) -> Callable:
            return _attach(func, "event_message_type", types=normalized)

        return decorator

    def permission_type(self, *types: Any, **kwargs: Any):
        normalized = [str(getattr(t, "value", t)) for t in types] or ["all"]

        def decorator(func: Callable) -> Callable:
            return _attach(func, "permission_type", types=normalized)

        return decorator

    def on_astrbot_loaded(self):
        def decorator(func: Callable) -> Callable:
            return _attach(func, "on_astrbot_loaded")

        return decorator

    def on_platform_loaded(self):
        def decorator(func: Callable) -> Callable:
            return _attach(func, "on_platform_loaded")

        return decorator

    def llm_tool(self, name: Any = None, **kwargs: Any):
        def decorator(func: Callable) -> Callable:
            return _attach(func, "llm_tool", name=name)

        return decorator

    def platform_adapter_type(self, *args: Any, **kwargs: Any):
        def decorator(func: Callable) -> Callable:
            return _attach(func, "noop")

        return decorator

    def after_message_sent(self, *args: Any, **kwargs: Any):
        def decorator(func: Callable) -> Callable:
            return _attach(func, "noop")

        return decorator

    def on_waiting_llm_request(self, *args: Any, **kwargs: Any):
        def decorator(func: Callable) -> Callable:
            return _attach(func, "noop")

        return decorator

    def on_llm_response(self, *args: Any, **kwargs: Any):
        def decorator(func: Callable) -> Callable:
            return _attach(func, "noop")

        return decorator


filter = _Filter()

__all__ = ["filter", "EventMessageType", "PermissionType"]
