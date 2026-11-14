from __future__ import annotations

from astrbot.api.event import AstrMessageEvent, filter
from astrbot.api.star import Context, Star, register


@register("hello_world", "NekoMate", "AstrBot 兼容示例：打个招呼", "1.0.0")
class HelloWorld(Star):
    """演示最小可用的 AstrBot 插件结构。"""

    def __init__(self, context: Context):
        super().__init__(context)

    @filter.command("hello", alias={"hi", "你好", "打招呼"})
    async def hello(self, event: AstrMessageEvent):
        yield event.plain_result(
            f"你好呀，{event.get_sender_name()}～ 这条消息来自 AstrBot 兼容插件 hello_world。"
        )
