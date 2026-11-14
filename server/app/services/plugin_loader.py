from __future__ import annotations

import asyncio
import importlib.util
import inspect
import json
import re
import shutil
import sys
import types
import traceback
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable, Iterable

import yaml
from sqlalchemy import select

from ..config import settings
from ..models import PluginRecord
from ..utils import truncate
from .logger import write_log

PLUGIN_SPACE = "nekomate_plugins"
ENTRY_CANDIDATES = ("main.py", "plugin.py", "__init__.py")
IGNORE_DIRS = {"__pycache__", ".git", "node_modules", ".venv", "venv"}


@dataclass
class HandlerInfo:
    kind: str
    meta: dict[str, Any]
    func: Callable


@dataclass
class DispatchResult:
    text: str
    plugin: str
    command: str | None = None


@dataclass
class PluginRuntime:
    slug: str
    path: Path
    name: str
    version: str = "1.0.0"
    author: str = ""
    desc: str = ""
    source: str = "local"
    enabled: bool = True
    config: dict[str, Any] = field(default_factory=dict)
    config_schema: dict[str, Any] = field(default_factory=dict)
    commands: list[str] = field(default_factory=list)
    handlers: list[HandlerInfo] = field(default_factory=list)
    instance: Any = None
    module: Any = None
    package: str | None = None
    status: str = "loaded"
    error: str | None = None


def _read_metadata(path: Path) -> dict[str, Any]:
    meta: dict[str, Any] = {}
    for candidate in ("metadata.yaml", "metadata.yml", "plugin.yaml", "plugin.json"):
        f = path / candidate
        if not f.exists():
            continue
        try:
            if f.suffix in (".yaml", ".yml"):
                data = yaml.safe_load(f.read_text(encoding="utf-8")) or {}
            else:
                data = json.loads(f.read_text(encoding="utf-8"))
            if isinstance(data, dict):
                meta.update(data)
        except Exception:
            continue
    schema_file = path / "_conf_schema.json"
    if not schema_file.exists():
        schema_file = path / "config_schema.json"
    schema: dict[str, Any] = {}
    if schema_file.exists():
        try:
            raw = json.loads(schema_file.read_text(encoding="utf-8"))
            if isinstance(raw, dict):
                schema = raw
        except Exception:
            schema = {}
    meta["__schema__"] = schema
    return meta


def _find_entry(path: Path) -> Path | None:
    for name in ENTRY_CANDIDATES:
        f = path / name
        if f.exists() and f.is_file():
            return f
    for f in sorted(path.glob("*.py")):
        if f.name.startswith("_"):
            continue
        return f
    return None


def _normalize_name(value: str) -> str:
    text = re.sub(r"^astrbot_plugin_", "", value or "")
    text = re.sub(r"[^A-Za-z0-9_]+", "_", text)
    return text or "plugin"


class PluginBridge:
    """宿主桥：把 AstrBot Context 的能力接到 NekoMate。"""

    def __init__(self, runtime: PluginRuntime, manager: "PluginManager") -> None:
        self.runtime = runtime
        self.manager = manager
        self.model_name = ""

    def get_plugin_config(self) -> dict[str, Any]:
        return dict(self.runtime.config or {})

    def get_provider(self):
        from ..astrbot_provider import PluginProvider

        return PluginProvider(self)

    async def send_message(self, umo: str, message_chain: Any) -> None:
        await write_log(
            "info",
            "plugin",
            f"插件 {self.runtime.name} 请求发送消息（宿主直发未启用）",
            umo=umo,
            preview=str(message_chain)[:200],
        )

    def register_web_api(self, route: str, view_handler: Any, methods: Any = None, desc: str = "") -> None:
        return None

    async def text_chat(self, prompt: str, contexts: list | None = None, **kwargs: Any) -> str:
        from ..database import SessionLocal
        from ..ai.providers import ProviderError, chat as provider_chat, pick_provider

        if SessionLocal is None:
            return ""
        async with SessionLocal() as session:
            spec = await pick_provider(session, "chat")
            self.model_name = spec.model
            if spec.is_demo:
                return "（演示模型）插件调用了宿主模型，但尚未配置真实的对话模型。"
            messages: list[dict[str, str]] = [
                {"role": "system", "content": "你是一个微信风格聊天插件的模型调用后端，回答简洁自然。"}
            ]
            for item in contexts or []:
                if isinstance(item, dict) and item.get("role"):
                    messages.append({"role": str(item["role"]), "content": str(item.get("content", ""))})
            messages.append({"role": "user", "content": prompt})
            try:
                return await provider_chat(spec, messages, temperature=0.7, max_tokens=600)
            except ProviderError as exc:
                await write_log("warning", "plugin", f"插件模型调用失败: {exc}")
                return ""


class PluginManager:
    def __init__(self) -> None:
        self.plugins: dict[str, PluginRuntime] = {}
        self.load_errors: list[str] = []

    # ------------------------------------------------------------------ load
    def _ensure_package_space(self) -> None:
        if PLUGIN_SPACE not in sys.modules:
            pkg = types.ModuleType(PLUGIN_SPACE)
            pkg.__path__ = [str(settings.plugins_dir)]  # type: ignore[attr-defined]
            sys.modules[PLUGIN_SPACE] = pkg

    def copy_builtin_plugins(self) -> list[str]:
        copied: list[str] = []
        src_root = settings.builtin_plugins_dir
        if not src_root.exists():
            return copied
        for src in src_root.iterdir():
            if not src.is_dir() or src.name.startswith("__"):
                continue
            dest = settings.plugins_dir / src.name
            if dest.exists():
                continue
            shutil.copytree(src, dest)
            copied.append(src.name)
        return copied

    async def load_all(self) -> None:
        self._ensure_package_space()
        settings.plugins_dir.mkdir(parents=True, exist_ok=True)
        copied = self.copy_builtin_plugins()
        if copied:
            await write_log("info", "plugin", f"已安装内置插件: {', '.join(copied)}")

        dirs = [d for d in sorted(settings.plugins_dir.iterdir()) if d.is_dir() and d.name not in IGNORE_DIRS]
        for path in dirs:
            await self.load_dir(path, source="builtin" if (settings.builtin_plugins_dir / path.name).exists() else "local")
        await self._sync_missing()

    async def _sync_missing(self) -> None:
        from ..database import SessionLocal

        if SessionLocal is None:
            return
        async with SessionLocal() as session:
            rows = (await session.execute(select(PluginRecord))).scalars().all()
            for row in rows:
                if row.slug not in self.plugins:
                    row.status = "failed"
                    row.error = "插件目录不存在或加载失败"
            await session.commit()

    async def load_dir(self, path: Path, *, source: str = "local") -> PluginRuntime | None:
        slug = path.name
        entry = _find_entry(path)
        if entry is None:
            await write_log("warning", "plugin", f"插件目录 {slug} 中没有可用的 Python 入口文件")
            return None
        meta = _read_metadata(path)
        display_name = str(meta.get("display_name") or "").strip()
        runtime = PluginRuntime(
            slug=slug,
            path=path,
            name=display_name or str(meta.get("name") or slug),
            version=str(meta.get("version") or "1.0.0"),
            author=str(meta.get("author") or ""),
            desc=str(meta.get("desc") or meta.get("description") or ""),
            source=source,
            config_schema=dict(meta.get("__schema__") or {}),
        )

        pkg_name = f"{PLUGIN_SPACE}.{_normalize_name(slug)}"
        try:
            self._unload_module(runtime)
            mod_spec = importlib.util.spec_from_file_location(
                f"{pkg_name}.{entry.stem}",
                entry,
                submodule_search_locations=[str(path)],
            )
            if mod_spec is None or mod_spec.loader is None:
                raise RuntimeError(f"无法创建模块 spec: {entry}")
            module = importlib.util.module_from_spec(mod_spec)
            pkg = types.ModuleType(pkg_name)
            pkg.__path__ = [str(path)]  # type: ignore[attr-defined]
            sys.modules[pkg_name] = pkg
            sys.modules[f"{pkg_name}.{entry.stem}"] = module
            old_path = list(sys.path)
            sys.path.insert(0, str(path))
            try:
                mod_spec.loader.exec_module(module)
            finally:
                sys.path[:] = old_path

            from astrbot.api.star import Star

            star_cls = None
            for name, obj in vars(module).items():
                if inspect.isclass(obj) and issubclass(obj, Star) and obj is not Star:
                    if hasattr(obj, "__astrbot_meta__") or star_cls is None:
                        star_cls = obj
                        if hasattr(obj, "__astrbot_meta__"):
                            break
            if star_cls is None:
                raise RuntimeError("未找到 Star 子类，插件可能不是 AstrBot 兼容格式")

            decorator_meta = getattr(star_cls, "__astrbot_meta__", {}) or {}
            runtime.name = str(meta.get("name") or decorator_meta.get("name") or runtime.name)
            runtime.version = str(meta.get("version") or decorator_meta.get("version") or runtime.version)
            runtime.author = str(meta.get("author") or decorator_meta.get("author") or runtime.author)
            runtime.desc = str(meta.get("desc") or decorator_meta.get("desc") or runtime.desc)

            bridge = PluginBridge(runtime, self)
            try:
                from astrbot.api.star import Context

                context = Context(bridge)
            except Exception:
                context = None
            runtime.instance = self._instantiate(star_cls, context, runtime)
            runtime.handlers = self._collect_handlers(runtime.instance, star_cls)
            runtime.commands = sorted({c for h in runtime.handlers for c in (h.meta.get("names") or []) if c})
            runtime.module = module
            runtime.package = pkg_name
            runtime.status = "loaded"
            runtime.error = None

            # 初始化钩子（如果有）
            init_fn = getattr(runtime.instance, "initialize", None)
            if callable(init_fn):
                try:
                    res = init_fn()
                    if inspect.isawaitable(res):
                        await asyncio.wait_for(res, timeout=30)
                except Exception as exc:
                    await write_log("warning", "plugin", f"插件 {runtime.name} initialize 失败: {exc}")

            for h in runtime.handlers:
                if h.kind == "on_astrbot_loaded":
                    try:
                        res = h.func(runtime.instance)
                        if inspect.isawaitable(res):
                            await res
                    except Exception:
                        pass

            self.plugins[slug] = runtime
            await write_log(
                "info",
                "plugin",
                f"插件已加载: {runtime.name} v{runtime.version}（命令: {', '.join(runtime.commands) or '无'}）",
                slug=slug,
            )
        except Exception as exc:
            runtime.status = "failed"
            runtime.error = f"{type(exc).__name__}: {exc}"
            self.plugins[slug] = runtime
            await write_log(
                "error",
                "plugin",
                f"插件 {slug} 加载失败: {exc}\n{traceback.format_exc()[:800]}",
                slug=slug,
            )
        await self._persist(runtime)
        return runtime

    def _instantiate(self, cls: type, context: Any, runtime: PluginRuntime) -> Any:
        try:
            sig = inspect.signature(cls.__init__)
            params = [p for p in list(sig.parameters.values())[1:]]
            if len(params) >= 2:
                return cls(context, runtime.config)
            return cls(context)
        except Exception:
            try:
                return cls(context)
            except Exception:
                return cls()

    def _collect_handlers(self, instance: Any, cls: type) -> list[HandlerInfo]:
        handlers: list[HandlerInfo] = []
        seen: set[tuple[str, str]] = set()
        for name in dir(cls):
            try:
                attr = getattr(cls, name)
            except Exception:
                continue
            metas = getattr(attr, "__astrbot_handlers__", None)
            if not metas:
                continue
            bound = getattr(instance, name, None)
            if not callable(bound):
                continue
            for meta in metas:
                key = (str(meta.get("kind")), name)
                if key in seen:
                    continue
                seen.add(key)
                handlers.append(HandlerInfo(kind=str(meta.get("kind")), meta=meta, func=bound))
        handlers.sort(key=lambda h: int(h.meta.get("priority") or 0))
        return handlers

    def _unload_module(self, runtime: PluginRuntime) -> None:
        prefix = f"{PLUGIN_SPACE}.{_normalize_name(runtime.slug)}"
        for name in list(sys.modules):
            if name == prefix or name.startswith(prefix + "."):
                sys.modules.pop(name, None)

    # ------------------------------------------------------------- persistence
    async def _persist(self, runtime: PluginRuntime) -> None:
        from ..database import SessionLocal

        if SessionLocal is None:
            return
        async with SessionLocal() as session:
            row = (
                await session.execute(select(PluginRecord).where(PluginRecord.slug == runtime.slug))
            ).scalars().first()
            if row is None:
                row = PluginRecord(
                    slug=runtime.slug,
                    name=runtime.name,
                    path=str(runtime.path),
                    source=runtime.source,
                    enabled=True,
                    config=self._default_config(runtime.config_schema),
                )
                session.add(row)
            # 数据库中的启停状态与配置优先
            runtime.enabled = bool(row.enabled)
            if row.config:
                runtime.config = {**self._default_config(runtime.config_schema), **row.config}
            else:
                runtime.config = {**self._default_config(runtime.config_schema), **(runtime.config or {})}
                row.config = runtime.config
            row.name = runtime.name
            row.version = runtime.version
            row.author = runtime.author
            row.description = runtime.desc
            row.path = str(runtime.path)
            row.config_schema = runtime.config_schema
            row.commands = runtime.commands
            row.status = runtime.status
            row.error = runtime.error
            await session.commit()

    def _default_config(self, schema: dict[str, Any]) -> dict[str, Any]:
        values: dict[str, Any] = {}
        for key, spec in (schema or {}).items():
            if isinstance(spec, dict):
                if "default" in spec:
                    values[key] = spec["default"]
                elif spec.get("type") in ("string", "text"):
                    values[key] = ""
                elif spec.get("type") in ("int", "number", "float"):
                    values[key] = 0
                elif spec.get("type") == "bool":
                    values[key] = False
                elif spec.get("type") in ("list", "file", "select"):
                    values[key] = [] if spec.get("type") == "list" else ""
        return values

    # ------------------------------------------------------------------ admin
    async def set_enabled(self, slug: str, enabled: bool) -> bool:
        from ..database import SessionLocal

        runtime = self.plugins.get(slug)
        if runtime is None:
            return False
        runtime.enabled = enabled
        if SessionLocal is None:
            return True
        async with SessionLocal() as session:
            row = (await session.execute(select(PluginRecord).where(PluginRecord.slug == slug))).scalars().first()
            if row:
                row.enabled = enabled
                await session.commit()
        await write_log("info", "plugin", f"插件 {slug} 已{'启用' if enabled else '停用'}")
        return True

    async def update_config(self, slug: str, config: dict[str, Any]) -> bool:
        from ..database import SessionLocal

        runtime = self.plugins.get(slug)
        if runtime is None:
            return False
        runtime.config = {**self._default_config(runtime.config_schema), **(config or {})}
        if runtime.instance is not None:
            try:
                runtime.instance.config = runtime.config
            except Exception:
                pass
        if SessionLocal is None:
            return True
        async with SessionLocal() as session:
            row = (await session.execute(select(PluginRecord).where(PluginRecord.slug == slug))).scalars().first()
            if row:
                row.config = runtime.config
                await session.commit()
        return True

    async def reload(self, slug: str | None = None) -> None:
        if slug:
            runtime = self.plugins.get(slug)
            path = runtime.path if runtime else settings.plugins_dir / slug
            self.plugins.pop(slug, None)
            await self.load_dir(Path(path), source=runtime.source if runtime else "local")
            return
        self.plugins.clear()
        for path in sorted(settings.plugins_dir.iterdir()):
            if path.is_dir() and path.name not in IGNORE_DIRS:
                await self.load_dir(path)

    async def delete(self, slug: str) -> bool:
        from ..database import SessionLocal

        runtime = self.plugins.pop(slug, None)
        path = runtime.path if runtime else settings.plugins_dir / slug
        self._unload_module(PluginRuntime(slug=slug, path=path, name=slug))
        try:
            if path.exists() and path.is_dir():
                shutil.rmtree(path)
        except Exception as exc:
            await write_log("warning", "plugin", f"删除插件目录失败: {exc}")
        if SessionLocal is not None:
            async with SessionLocal() as session:
                row = (await session.execute(select(PluginRecord).where(PluginRecord.slug == slug))).scalars().first()
                if row:
                    await session.delete(row)
                    await session.commit()
        await write_log("info", "plugin", f"插件 {slug} 已删除")
        return True

    # --------------------------------------------------------------- dispatch
    async def dispatch(
        self,
        *,
        text: str,
        user: Any,
        conversation: Any,
        ai_friend: Any = None,
        sender_name: str = "",
        group_id: Any = None,
    ) -> DispatchResult | None:
        if not self.plugins:
            return None
        from astrbot.api.event import AstrMessageEvent
        from astrbot.api.message_components import BaseMessageComponent

        event = AstrMessageEvent(
            message_str=text or "",
            session_id=str(getattr(conversation, "id", "")),
            sender_id=str(getattr(user, "id", "")),
            sender_name=sender_name or getattr(user, "nickname", "") or "",
            group_id=str(group_id) if group_id else None,
            raw_user=user,
            conversation=conversation,
        )

        stripped = (text or "").strip()
        first_token = stripped.split()[0] if stripped else ""
        command = first_token.lstrip("/!#") if first_token else ""

        for slug, runtime in self.plugins.items():
            if not runtime.enabled or runtime.status != "loaded":
                continue
            for handler in runtime.handlers:
                if handler.kind not in ("command", "command_group", "regex", "event_message_type"):
                    continue
                if not self._matches(handler, command, stripped, event):
                    continue
                try:
                    output = await asyncio.wait_for(self._invoke(handler, event), timeout=45)
                except asyncio.TimeoutError:
                    await write_log("warning", "plugin", f"插件 {runtime.name} 处理超时")
                    continue
                except Exception as exc:
                    await write_log(
                        "error", "plugin", f"插件 {runtime.name} 执行异常: {exc}\n{traceback.format_exc()[:600]}"
                    )
                    continue
                text_out = self._result_to_text(output)
                if text_out:
                    await write_log(
                        "info",
                        "plugin",
                        f"插件 {runtime.name} 命中 {handler.kind}: {truncate(stripped, 40)}",
                        slug=slug,
                    )
                    return DispatchResult(text=text_out, plugin=runtime.name, command=command or None)
                if event.is_stopped():
                    return None
        return None

    def _matches(self, handler: HandlerInfo, command: str, text: str, event: Any) -> bool:
        if handler.kind in ("command", "command_group"):
            names = [str(n).lower() for n in handler.meta.get("names") or []]
            if not names:
                return False
            return command.lower() in names
        if handler.kind == "regex":
            pattern = handler.meta.get("pattern") or ""
            try:
                return bool(re.search(pattern, text))
            except re.error:
                return False
        if handler.kind == "event_message_type":
            types = handler.meta.get("types") or ["all"]
            return "all" in types or ("group" in types and bool(event.group_id)) or (
                "private" in types and not event.group_id
            )
        return False

    async def _invoke(self, handler: HandlerInfo, event: Any) -> Any:
        result = handler.func(event)
        if inspect.isasyncgen(result):
            chunks = []
            async for item in result:
                chunks.append(item)
            return chunks
        if inspect.isgenerator(result):
            return list(result)
        if inspect.isawaitable(result):
            return await result
        return result

    def _result_to_text(self, result: Any) -> str:
        from astrbot.api.event import MessageEventResult
        from astrbot.api.message_components import BaseMessageComponent

        if result is None:
            return ""
        if isinstance(result, str):
            return result.strip()
        if isinstance(result, MessageEventResult):
            return result.to_text()
        if isinstance(result, BaseMessageComponent):
            return result.to_text().strip()
        if isinstance(result, (list, tuple)):
            parts = []
            for item in result:
                parts.append(self._result_to_text(item))
            return "".join(p for p in parts if p).strip()
        return str(result).strip()


plugin_manager = PluginManager()
