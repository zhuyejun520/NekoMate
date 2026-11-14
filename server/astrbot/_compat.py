"""Fallback loader for not-yet-implemented ``astrbot.*`` modules.

Real AstrBot plugins sometimes import modules that this compatibility layer does
not ship. Instead of failing the whole plugin import, we hand out a permissive
stub module whose attributes behave like no-op callables/decorators.
"""

from __future__ import annotations

import logging
import sys
import types
from importlib.abc import Loader, MetaPathFinder
from importlib.machinery import ModuleSpec

logger = logging.getLogger("nekomate.plugin_compat")


class _StubAttribute:
    def __init__(self, name: str) -> None:
        object.__setattr__(self, "_name", name)

    def __call__(self, *args, **kwargs):
        if len(args) == 1 and callable(args[0]) and not kwargs:
            return args[0]
        return _StubAttribute(f"{self._name}()")

    def __getattr__(self, item: str):
        return _StubAttribute(f"{self._name}.{item}")

    def __iter__(self):
        return iter(())

    def __repr__(self) -> str:  # pragma: no cover
        return f"<astrbot stub {self._name}>"


class _StubModule(types.ModuleType):
    def __getattr__(self, name: str):
        if name.startswith("__"):
            raise AttributeError(name)
        value = _StubAttribute(f"{self.__name__}.{name}")
        setattr(self, name, value)
        return value


class _StubLoader(Loader):
    def create_module(self, spec: ModuleSpec):
        module = _StubModule(spec.name)
        module.__path__ = []
        return module

    def exec_module(self, module) -> None:
        logger.debug("stubbed missing astrbot module: %s", module.__name__)


class _StubFinder(MetaPathFinder):
    prefix = "astrbot."

    def find_spec(self, fullname: str, path=None, target=None):
        if not fullname.startswith(self.prefix):
            return None
        logger.info("插件引用了未实现的模块 %s，已使用空实现代替", fullname)
        return ModuleSpec(fullname, _StubLoader(), is_package=True)


if not any(isinstance(f, _StubFinder) for f in sys.meta_path):
    sys.meta_path.append(_StubFinder())
