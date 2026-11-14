"""验证 OpenAI 兼容链路：provider 测试、对话、分析记忆、图像生成。"""
from __future__ import annotations

import json
import sys
import time

import httpx

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8099"
MOCK = sys.argv[2] if len(sys.argv) > 2 else "http://127.0.0.1:8100/v1"
client = httpx.Client(base_url=BASE, timeout=60.0)
fail = 0


def check(name, cond, detail=""):
    global fail
    print(("[PASS] " if cond else "[FAIL] ") + name + ("" if cond else " :: " + str(detail)[:400]))
    if not cond:
        fail += 1


def req(method, path, token=None, **kw):
    headers = kw.pop("headers", {})
    if token:
        headers["Authorization"] = f"Bearer {token}"
    return client.request(method, path, headers=headers, **kw)


r = req("POST", "/api/admin/auth/login", json={"username": "admin", "password": "change-me-now"})
assert r.status_code == 200, r.text
admin = r.json()["token"]

r = req("POST", "/api/admin/providers", admin, json={
    "name": "Mock 模型", "kind": "chat", "base_url": MOCK, "api_key": "sk-mock", "model": "mock-chat",
    "enabled": True, "priority": 1})
check("create live provider", r.status_code == 200, r.text)
pid = r.json()["id"] if r.status_code == 200 else 0

r = req("POST", f"/api/admin/providers/{pid}/test", admin)
check("provider test ok", r.status_code == 200 and r.json()["result"]["ok"], r.text)
r = req("POST", "/api/admin/providers/test", admin, json={
    "name": "mock-image", "kind": "image", "base_url": MOCK, "api_key": "sk-mock", "model": "mock-image"})
check("image provider adhoc test", r.status_code == 200 and r.json()["result"]["ok"], r.text)

r = req("POST", "/api/admin/providers", admin, json={
    "name": "Mock 图像", "kind": "image", "base_url": MOCK, "api_key": "sk-mock", "model": "mock-image", "enabled": True})
check("create image provider", r.status_code == 200, r.text)

# 用户 + AI 对话走真实 provider 链路
uname = f"live{int(time.time()) % 100000}"
r = req("POST", "/api/v1/auth/register", json={"username": uname, "password": "test123456", "nickname": "真实链路"})
check("register", r.status_code == 200, r.text)
token = r.json()["token"]
ai = r.json().get("ai_friend") or {}
ai_id = ai.get("id")

r = req("POST", f"/api/v1/ai/friends/{ai_id}/chat", token,
        json={"content": "我叫小林，我喜欢冰美式，最近在准备考研，压力有点大"})
reply = r.json().get("reply", {}).get("content", "") if r.status_code == 200 else ""
check("live chat reply", r.status_code == 200 and "mock" in reply, json.dumps(r.json(), ensure_ascii=False)[:400])

time.sleep(2.5)
r = req("GET", f"/api/v1/ai/friends/{ai_id}/memories", token)
mems = r.json().get("memories", []) if r.status_code == 200 else []
check("analysis memory", any(m.get("keywords") for m in mems), json.dumps(mems, ensure_ascii=False)[:400])
check("analysis importance", any(m.get("kind") == "long" and m.get("importance", 0) >= 0.8 for m in mems),
      json.dumps(mems, ensure_ascii=False)[:300])

r = req("POST", f"/api/v1/ai/friends/{ai_id}/proactive/trigger", token)
check("live proactive", r.status_code == 200 and "mock" in r.json().get("message", {}).get("content", ""), r.text[:400])

r = req("POST", f"/api/v1/ai/friends/{ai_id}/image", token, json={"prompt": "夜色里的猫", "post": True})
check("live image", r.status_code == 200 and "mock.local" in (r.json().get("url") or ""), r.text[:400])

r = req("GET", "/api/admin/logs?category=provider&limit=5", admin)
check("provider logs", r.status_code == 200 and len(r.json()["logs"]) > 0, r.text[:300])

print(f"\nfailures: {fail}")
sys.exit(1 if fail else 0)
