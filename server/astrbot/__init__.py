"""AstrBot 兼容层（NekoMate 内置）。

这个包提供了一个最小可用的 AstrBot 运行时接口，使得为 AstrBot 编写的插件
（``from astrbot.api...``）可以不加修改地被 NekoMate 加载运行。
仅实现常用子集：命令、正则、事件类型过滤器、消息组件、Star 基类、Context。
"""

from __future__ import annotations

__version__ = "4.0.0-nekomate"
version = __version__

from . import _compat as _compat  # noqa: E402  (installs the fallback meta path finder)

__all__ = ["__version__", "version"]
