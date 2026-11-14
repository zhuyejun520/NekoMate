from __future__ import annotations

import asyncio
from collections import defaultdict
from typing import Any

from fastapi import WebSocket

from .logger import write_log


class ConnectionManager:
    def __init__(self) -> None:
        self._connections: dict[int, set[WebSocket]] = defaultdict(set)
        self._lock = asyncio.Lock()

    async def connect(self, user_id: int, ws: WebSocket) -> None:
        await ws.accept()
        async with self._lock:
            self._connections[user_id].add(ws)
        await write_log("info", "ws", f"用户 {user_id} 建立实时连接", user_id=user_id)

    async def disconnect(self, user_id: int, ws: WebSocket) -> None:
        async with self._lock:
            self._connections[user_id].discard(ws)
            if not self._connections[user_id]:
                self._connections.pop(user_id, None)

    def online(self, user_id: int) -> bool:
        return bool(self._connections.get(user_id))

    def online_count(self) -> int:
        return sum(len(v) for v in self._connections.values())

    async def send_user(self, user_id: int, payload: dict[str, Any]) -> int:
        sockets = list(self._connections.get(user_id, set()))
        dead: list[WebSocket] = []
        sent = 0
        for ws in sockets:
            try:
                await ws.send_json(payload)
                sent += 1
            except Exception:
                dead.append(ws)
        for ws in dead:
            await self.disconnect(user_id, ws)
        return sent

    async def broadcast(self, payload: dict[str, Any]) -> None:
        for user_id in list(self._connections.keys()):
            await self.send_user(user_id, payload)

    async def close_all(self) -> None:
        for user_id, sockets in list(self._connections.items()):
            for ws in list(sockets):
                try:
                    await ws.close()
                except Exception:
                    pass
        self._connections.clear()


manager = ConnectionManager()
