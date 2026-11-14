"""WebSocket 实时推送测试。"""
from __future__ import annotations

import asyncio
import json
import sys
import time

import httpx
import websockets

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8099"
WS = BASE.replace("http", "ws") + "/api/v1/ws"


async def main() -> int:
    client = httpx.Client(base_url=BASE, timeout=30.0)
    uname = f"ws{int(time.time()) % 100000}"
    r = client.post("/api/v1/auth/register", json={"username": uname, "password": "test123456", "nickname": "WS测试"})
    assert r.status_code == 200, r.text
    token = r.json()["token"]
    ai_id = r.json()["ai_friend"]["id"]
    headers = {"Authorization": f"Bearer {token}"}

    async with websockets.connect(f"{WS}?token={token}") as ws:
        hello = json.loads(await asyncio.wait_for(ws.recv(), timeout=10))
        print("ready:", hello)
        client.post(f"/api/v1/ai/friends/{ai_id}/chat", headers=headers, json={"content": "你好呀"})
        event = json.loads(await asyncio.wait_for(ws.recv(), timeout=25))
        print("event type:", event.get("type"), "content:", (event.get("message") or {}).get("content", "")[:40])
        assert event.get("type") == "message", event
        await ws.send(json.dumps({"type": "ping"}))
        pong = json.loads(await asyncio.wait_for(ws.recv(), timeout=10))
        print("pong:", pong.get("type"))
        assert pong.get("type") == "pong"
    print("WS OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
