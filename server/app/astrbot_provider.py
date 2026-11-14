from __future__ import annotations

from typing import Any

from .astrbot.api.provider import LLMResponse, Provider


class PluginProvider(Provider):
    """把宿主对话模型包装成 AstrBot 的 Provider 接口，供插件调用。"""

    def __init__(self, bridge: Any) -> None:
        super().__init__(bridge)

    async def text_chat(self, prompt: str = "", session_id: str | None = None, contexts: list | None = None,
                        **kwargs: Any) -> LLMResponse:
        text = ""
        if self._bridge is not None:
            text = await self._bridge.text_chat(prompt, contexts=contexts, **kwargs)
        return LLMResponse(completion_text=text or "")
