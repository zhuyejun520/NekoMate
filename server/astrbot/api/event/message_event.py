from __future__ import annotations

from typing import Any

from ..message_components import BaseMessageComponent, Image, Plain


class MessageEventResult(list):
    """AstrBot 的返回结果对象：本质是消息组件列表。"""

    def __init__(self, *args: Any) -> None:
        super().__init__(*args)
        self.is_stopped = False

    def message(self, component: Any):
        if component is not None:
            self.append(component)
        return self

    def plain(self, text: str):
        self.append(Plain(text))
        return self

    def url_image(self, url: str):
        self.append(Image(file=url))
        return self

    def use_t2i(self, *args: Any, **kwargs: Any):
        return self

    def error(self, message: str):
        self.append(Plain(message))
        return self

    def to_text(self) -> str:
        parts = []
        for item in self:
            if isinstance(item, BaseMessageComponent):
                parts.append(item.to_text())
            elif isinstance(item, str):
                parts.append(item)
        return "".join(parts).strip()


class AstrMessageEvent:
    """传递给插件处理函数的消息事件。"""

    def __init__(
        self,
        *,
        message_str: str = "",
        session_id: str = "",
        platform_name: str = "nekocyte",
        sender_id: str = "",
        sender_name: str = "",
        group_id: str | None = None,
        bridge: Any = None,
        raw_user: Any = None,
        conversation: Any = None,
    ) -> None:
        self.message_str = message_str or ""
        self.message_obj = self
        self.session_id = session_id or sender_id
        self.platform_name = platform_name
        self.sender_id = str(sender_id)
        self.sender_name = sender_name
        self.group_id = str(group_id) if group_id else None
        self.unified_msg_origin = f"{platform_name}:{('group' if group_id else 'private')}:{session_id or sender_id}"
        self.raw_user = raw_user
        self.conversation = conversation
        self.role = "member"
        self._extras: dict[str, Any] = {}
        self._stopped = False
        self._bridge = bridge
        self.is_at_or_wake_command = True

    # ---- identity helpers -------------------------------------------------
    def get_sender_id(self) -> str:
        return self.sender_id

    def get_sender_name(self) -> str:
        return self.sender_name

    def get_session_id(self) -> str:
        return self.session_id

    def get_group_id(self) -> str | None:
        return self.group_id

    def get_platform_name(self) -> str:
        return self.platform_name

    def get_message_str(self) -> str:
        return self.message_str

    def get_messages(self) -> list[Any]:
        return [Plain(self.message_str)]

    def get_message_type(self) -> str:
        return "group" if self.group_id else "private"

    def is_private_chat(self) -> bool:
        return not self.group_id

    def is_admin(self) -> bool:
        return False

    # ---- results ----------------------------------------------------------
    def plain_result(self, text: str) -> MessageEventResult:
        return MessageEventResult([Plain(text)])

    def image_result(self, url: str) -> MessageEventResult:
        return MessageEventResult([Image(file=url)])

    def make_result(self) -> MessageEventResult:
        return MessageEventResult()

    # ---- control ----------------------------------------------------------
    def stop_event(self) -> None:
        self._stopped = True

    def is_stopped(self) -> bool:
        return self._stopped

    def continue_event(self) -> None:
        self._stopped = False

    def set_extra(self, key: str, value: Any) -> None:
        self._extras[key] = value

    def get_extra(self, key: str = "", default: Any = None) -> Any:
        if not key:
            return self._extras
        return self._extras.get(key, default)

    async def send(self, result: Any) -> None:
        if self._bridge is not None:
            await self._bridge.send_message(self.unified_msg_origin, result)

    async def request_llm(self, prompt: str, **kwargs: Any) -> Any:
        if self._bridge is None:
            return None
        provider = self._bridge.get_provider()
        return await provider.text_chat(prompt=prompt, session_id=self.session_id, **kwargs)

    def __repr__(self) -> str:  # pragma: no cover
        return f"<AstrMessageEvent {self.sender_name}: {self.message_str[:30]}>"


__all__ = ["AstrMessageEvent", "MessageEventResult"]
