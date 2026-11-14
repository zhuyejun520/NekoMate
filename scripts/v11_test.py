"""v1.1 服务端功能测试：专属 Key、私人角色卡、客户端更新。"""
from __future__ import annotations

import sys
import time

import httpx

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8099"
MOCK = "http://127.0.0.1:8100/v1"
c = httpx.Client(base_url=BASE, timeout=60.0)
fail = 0


def check(name, cond, detail=""):
    global fail
    print(("[PASS] " if cond else "[FAIL] ") + name + ("" if cond else " :: " + str(detail)[:400]))
    if not cond:
        fail += 1


def call(method, path, token=None, **kw):
    h = kw.pop("headers", {})
    if token:
        h["Authorization"] = f"Bearer {token}"
    return c.request(method, path, headers=h, **kw)


r = call("POST", "/api/admin/auth/login", json={"username": "admin", "password": "change-me-now"})
check("admin login", r.status_code == 200, r.text)
admin = r.json()["token"]

# ---- two users
stamp = int(time.time()) % 1000000
ua = f"keya{stamp}"
ub = f"keyb{stamp}"
r = call("POST", "/api/v1/auth/register", json={"username": ua, "password": "test123456", "nickname": "KeyA"})
check("register A", r.status_code == 200, r.text)
ra = r.json()
ta, ida, aia = ra["token"], ra["user"]["id"], ra["ai_friend"]["id"]
r = call("POST", "/api/v1/auth/register", json={"username": ub, "password": "test123456", "nickname": "KeyB"})
check("register B", r.status_code == 200, r.text)
rb = r.json()
tb, idb, aib = rb["token"], rb["user"]["id"], rb["ai_friend"]["id"]

# ---- global bad provider + private mock provider for A
r = call("POST", "/api/admin/providers", admin, json={
    "name": "全局坏Key", "kind": "chat", "base_url": "http://127.0.0.1:9/v1",
    "api_key": "sk-global-bad", "model": "bad", "enabled": True, "priority": 50})
check("create global bad provider", r.status_code == 200, r.text)
r = call("POST", "/api/admin/providers", admin, json={
    "name": "A 专属 Key", "kind": "chat", "base_url": MOCK,
    "api_key": "sk-a-private", "model": "mock-chat", "enabled": True, "priority": 1,
    "owner_user_id": ida})
check("create private key for A", r.status_code == 200 and r.json()["provider"]["owner_user_id"] == ida, r.text)
private_pid = r.json()["id"] if r.status_code == 200 else 0

r = call("GET", f"/api/admin/providers?owner_user_id={ida}", admin)
check("filter private providers", r.status_code == 200 and all(p["owner_user_id"] == ida for p in r.json()["providers"]), r.text)

# A uses private mock; B uses global bad -> demo
r = call("POST", f"/api/v1/ai/friends/{aia}/chat", ta, json={"content": "测试一下专属key"})
reply_a = r.json().get("reply", {}).get("content", "") if r.status_code == 200 else ""
check("A uses private key", "mock" in reply_a, r.text[:500])
r = call("POST", f"/api/v1/ai/friends/{aib}/chat", tb, json={"content": "测试一下全局key"})
reply_b = r.json().get("reply", {}).get("content", "") if r.status_code == 200 else ""
check("B not using A's key", "mock" not in reply_b and "演示模式" in reply_b, r.text[:500])

# ---- private personas
r = call("GET", "/api/v1/ai/personas", ta)
check("A can create private", r.status_code == 200 and r.json().get("can_create_private") is True, r.text[:300])
r = call("GET", "/api/v1/ai/personas", tb)
check("B cannot create private", r.status_code == 200 and r.json().get("can_create_private") is False, r.text[:300])

persona_body = {
    "name": f"私人角色{stamp}",
    "avatar": "🦊",
    "tagline": "只属于我",
    "description": "测试私人角色",
    "personality": "温柔",
    "speaking_style": "短句",
    "system_prompt": "你就是这个私人角色",
    "greeting": "你来啦",
    "catchphrases": ["嗯哼"],
}
r = call("POST", "/api/v1/ai/personas", ta, json=persona_body)
check("A create private persona", r.status_code == 200, r.text)
pid = r.json()["persona"]["id"] if r.status_code == 200 else 0
r = call("POST", "/api/v1/ai/personas", tb, json=persona_body)
check("B create private denied", r.status_code == 403, r.text)
r = call("GET", "/api/v1/ai/personas", tb)
ids_b = [p["id"] for p in r.json().get("personas", [])]
check("B cannot see A's persona", pid not in ids_b, ids_b)
r = call("GET", "/api/v1/ai/personas", ta)
ids_a = [p["id"] for p in r.json().get("personas", [])]
check("A sees own persona", pid in ids_a, ids_a)
r = call("POST", "/api/v1/ai/friends", ta, json={"persona_id": pid})
check("A adds private persona", r.status_code == 200, r.text)

r = call("GET", "/api/admin/personas", admin)
row = next((p for p in r.json()["personas"] if p["id"] == pid), None)
check("admin sees private persona", row is not None and row.get("owner_id") == ida and not row.get("is_public"), str(row)[:300])

r = call("GET", f"/api/admin/users?q={ua}", admin)
user_a = next((u for u in r.json()["users"] if u["id"] == ida), None)
check("admin user key count", user_a is not None and user_a.get("private_keys", 0) >= 1, str(user_a)[:300])

# ---- app release
apk_bytes = b"PK\x03\x04nekocyte-test-apk-" + str(stamp).encode()
r = call("POST", "/api/admin/releases", admin, files={"file": ("NekoMate-test.apk", apk_bytes, "application/vnd.android.package-archive")},
         data={"version_code": "2", "version_name": "1.1.0", "changelog": "1. 测试更新\n2. 主题", "force_update": "false"})
check("upload release", r.status_code == 200 and r.json()["release"]["version_code"] == 2, r.text)
rel = r.json()["release"] if r.status_code == 200 else {}
r = call("GET", "/api/v1/app/version?platform=android&version_code=1")
data = r.json() if r.status_code == 200 else {}
check("version update true", data.get("update") is True and data.get("latest", {}).get("version_name") == "1.1.0", r.text[:400])
url = data.get("latest", {}).get("download_url", "")
r = c.get(url)
check("download release", r.status_code == 200 and r.content == apk_bytes, f"{r.status_code} {r.content[:30]}")
r = call("GET", "/api/v1/app/version?platform=android&version_code=2")
check("version update false", r.status_code == 200 and r.json().get("update") is False, r.text[:200])
if rel:
    r = call("DELETE", f"/api/admin/releases/{rel['id']}", admin)
    check("delete release", r.status_code == 200, r.text)

print(f"\nfailures: {fail}")
sys.exit(1 if fail else 0)
