"""1.3 功能测试：好感度、免打扰、聊天图片、日常删除。"""
from __future__ import annotations

import sys
import time
from pathlib import Path

import httpx

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8096"
MOCK = "http://127.0.0.1:8100/v1"
c = httpx.Client(base_url=BASE, timeout=60.0)
fail = 0


def check(name, cond, detail=""):
    global fail
    print(("[PASS] " if cond else "[FAIL] ") + name + ("" if cond else " :: " + str(detail)[:500]))
    if not cond:
        fail += 1


def call(method, path, token=None, **kw):
    h = kw.pop("headers", {})
    if token:
        h["Authorization"] = f"Bearer {token}"
    return c.request(method, path, headers=h, **kw)


r = call("POST", "/api/admin/auth/login", json={"username": "admin", "password": "change-me-now"})
admin = r.json()["token"]
for kind, name in (("chat", "mock-chat"), ("analysis", "mock-analysis"), ("vision", "mock-vision")):
    r = call("POST", "/api/admin/providers", admin, json={
        "name": name, "kind": kind, "base_url": MOCK, "api_key": "sk-mock",
        "model": "mock-chat", "enabled": True, "priority": 1})
    check(f"provider {kind}", r.status_code == 200, r.text)

stamp = int(time.time()) % 1000000
uname = f"v13user{stamp}"
r = call("POST", "/api/v1/auth/register", json={"username": uname, "password": "test123456", "nickname": "测试"})
check("register", r.status_code == 200, r.text)
data = r.json()
token, uid, ai_id = data["token"], data["user"]["id"], data["ai_friend"]["id"]
conv_id = call("GET", f"/api/v1/ai/friends/{ai_id}/conversation", token).json()["conversation_id"]

r = call("GET", f"/api/v1/ai/friends/{ai_id}", token)
check("default affinity 0", r.json()["ai_friend"]["settings"].get("affinity") == 0 and r.json()["ai_friend"]["settings"].get("affinity_enabled") is True, r.text)

r = call("POST", f"/api/v1/ai/friends/{ai_id}/chat", token, json={"content": "谢谢你今天陪我"})
check("chat thank", r.status_code == 200, r.text)
time.sleep(2)
r = call("GET", f"/api/v1/ai/friends/{ai_id}", token)
aff = r.json()["ai_friend"]["settings"].get("affinity")
check("affinity increased", aff >= 1, r.text)

r = call("PUT", f"/api/v1/ai/friends/{ai_id}", token, json={"settings": {"affinity_enabled": False}})
check("disable affinity", r.status_code == 200, r.text)
r = call("POST", f"/api/v1/ai/friends/{ai_id}/chat", token, json={"content": "我很喜欢你"})
time.sleep(2)
r = call("GET", f"/api/v1/ai/friends/{ai_id}", token)
check("affinity unchanged while disabled", r.json()["ai_friend"]["settings"].get("affinity") == aff, r.text)

r = call("PUT", f"/api/v1/ai/friends/{ai_id}", token, json={"settings": {"affinity_enabled": True, "quiet_start": "23:00", "quiet_end": "08:00"}})
check("quiet hours saved", r.status_code == 200, r.text)
r = call("GET", f"/api/v1/ai/friends/{ai_id}", token)
check("quiet hours returned", r.json()["ai_friend"]["settings"].get("quiet_start") == "23:00" and r.json()["ai_friend"]["settings"].get("quiet_end") == "08:00", r.text)

png = Path(__file__).resolve().parent.parent / "android" / "shots" / "66-editor-image-new.png"
if not png.exists():
    png = Path(__file__).resolve().parent.parent / "android" / "shots" / "40-light-main.png"
r = call("POST", "/api/v1/upload", token, files={"file": ("chat-test.png", png.read_bytes(), "image/png")})
check("upload image", r.status_code == 200, r.text)
image_url = r.json()["url"]
r = call("POST", f"/api/v1/conversations/{conv_id}/messages", token, json={
    "content": "看看这个", "content_type": "image", "image_url": image_url})
check("send image", r.status_code == 200, r.text)
msg = r.json().get("message", {})
check("image message meta", msg.get("content_type") == "image" and msg.get("meta", {}).get("url") == image_url, msg)
check("vision description stored", bool(msg.get("meta", {}).get("vision")), msg.get("meta"))

r = call("POST", "/api/v1/moments", token, json={"content": "待删除的日常", "images": []})
check("create moment", r.status_code == 200, r.text)
mid = r.json()["moment"]["id"]
r = call("DELETE", f"/api/v1/moments/{mid}", token)
check("delete moment", r.status_code == 200, r.text)
r = call("GET", "/api/v1/moments?scope=friends", token)
ids = [m["id"] for m in r.json().get("moments", [])]
check("moment removed", mid not in ids, ids)

print(f"\nfailures: {fail}")
sys.exit(1 if fail else 0)
