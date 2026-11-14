from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any


@dataclass
class LLMResponse:
    role: str = "assistant"
    completion_text: str = ""
    raw_completion: Any = None
    usage: dict[str, Any] = field(default_factory=dict)

    def __str__(self) -> str:  # pragma: no cover
        return self.completion_text


class Provider:
    """供插件调用的简化模型提供者接口。"""

    def __init__(self, bridge: Any = None) -> None:
        self._bridge = bridge

    async def text_chat(self, prompt: str = "", session_id: str | None = None, contexts: list | None = None, **kwargs: Any) -> LLMResponse:
        text = ""
        if self._bridge is not None:
            text = await self._bridge.text_chat(prompt, contexts=contexts, **kwargs)
        return LLMResponse(completion_text=text or "")

    async def text_chat_stream(self, *args: Any, **kwargs: Any):
        response = await self.text_chat(*args, **kwargs)
        yield response.completion_text

    def get_model(self) -> str:
        return getattr(self._bridge, "model_name", "unknown") if self._bridge else "unknown"


__all__ = ["Provider", "LLMResponse"]
