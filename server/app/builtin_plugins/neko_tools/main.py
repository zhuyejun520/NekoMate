from __future__ import annotations

import random
import re
from datetime import datetime

from astrbot.api.event import AstrMessageEvent, filter
from astrbot.api.star import Context, Star, register

FORTUNES = [
    "今天适合把拖了很久的事做掉，做完会有种奇怪的爽感。",
    "会遇到一点小麻烦，但都在你能解决的范围内。",
    "宜喝热水，忌熬夜，别的都随缘。",
    "今天说的话比平时更容易被记住，注意措辞。",
    "有意外的小惊喜，可能在午饭之后。",
    "适合断舍离：删掉一个不再用的 App，或者一个不再联系的人。",
    "运气一般，但你今天的耐心值很高。",
    "会遇到一个让你笑出来的人。",
]

SHARP_FORTUNES = [
    "运势：适合上班摸鱼，但别被抓到。",
    "运势：你今天最大的敌人是自己的闹钟。",
    "运势：嘴上说着随便，心里早有答案。",
    "运势：会遇到废话很多的人，建议微笑点头。",
]


@register("neko_tools", "NekoMate", "骰子 / 运势 / 时间 小工具", "1.0.0")
class NekoTools(Star):
    def __init__(self, context: Context):
        super().__init__(context)

    def _cfg(self) -> dict:
        try:
            cfg = self.context.get_config() if self.context else {}
        except Exception:
            cfg = {}
        return cfg if isinstance(cfg, dict) else {}

    @filter.command("roll", alias={"骰子", "dice", "掷骰"})
    async def roll(self, event: AstrMessageEvent):
        cfg = self._cfg()
        try:
            max_sides = max(2, int(cfg.get("max_sides", 100) or 100))
        except (TypeError, ValueError):
            max_sides = 100
        text = event.get_message_str()
        match = re.search(r"(\d*)\s*[dD](\d+)", text)
        if match:
            count = min(max(int(match.group(1) or 1), 1), 10)
            sides = min(max(int(match.group(2)), 2), max_sides)
        else:
            count, sides = 1, 6
        rolls = [random.randint(1, sides) for _ in range(count)]
        total = sum(rolls)
        if count == 1:
            yield event.plain_result(f"🎲 {rolls[0]}（1-{sides}）")
        else:
            yield event.plain_result(f"🎲 {' + '.join(map(str, rolls))} = {total}")

    @filter.command("运势", alias={"fortune", "今日运势"})
    async def fortune(self, event: AstrMessageEvent):
        style = str(self._cfg().get("fortune_style") or "温和")
        pool = SHARP_FORTUNES if style == "毒舌" else FORTUNES
        yield event.plain_result(random.choice(pool))

    @filter.regex(r"^(现在几点|几点了|time)$")
    async def now(self, event: AstrMessageEvent):
        yield event.plain_result(datetime.now().strftime("现在是 %H:%M:%S，别忘了喝水。"))
