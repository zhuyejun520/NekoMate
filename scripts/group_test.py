"""群聊成员/AI 进群测试。"""
from __future__ import annotations

import sys
import time

import httpx

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8097"
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
admin = r.json()["token"]
stamp = int(time.time()) % 1000000
owner_name, friend_name, friend2_name = f"gowner{stamp}", f"gfriend{stamp}", f"gfriend2{stamp}"
r = call("POST", "/api/v1/auth/register", json={"username": owner_name, "password": "test123456", "nickname": "群主"})
owner = r.json()
ot, oid, oai = owner["token"], owner["user"]["id"], owner["ai_friend"]["id"]
r = call("POST", "/api/v1/auth/register", json={"username": friend_name, "password": "test123456", "nickname": "好友一"})
friend = r.json()
ft, fid = friend["token"], friend["user"]["id"]
r = call("POST", "/api/v1/auth/register", json={"username": friend2_name, "password": "test123456", "nickname": "好友二"})
friend2 = r.json()
f2id = friend2["user"]["id"]

# private key for owner so group AI uses mock
r = call("POST", "/api/admin/providers", admin, json={
    "name": f"{owner_name} key", "kind": "chat", "base_url": MOCK, "api_key": "sk-group",
    "model": "mock-chat", "enabled": True, "priority": 1, "owner_user_id": oid})
check("private key", r.status_code == 200, r.text)

r = call("POST", "/api/v1/conversations/group", ot, json={"title": "测试群", "member_ids": [fid]})
check("create group", r.status_code == 200, r.text)
conv = r.json()["conversation"]
cid = conv["id"]
check("group member count", conv.get("members_count") == 2, conv)

r = call("POST", f"/api/v1/conversations/{cid}/members", ot, json={"ai_friend_ids": [oai]})
check("invite AI", r.status_code == 200 and len(r.json().get("added_ai", [])) == 1, r.text)

r = call("GET", f"/api/v1/conversations/{cid}", ot)
detail = r.json()["conversation"]
check("detail has AI member", len(detail.get("ai_members", [])) == 1 and detail["ai_members"][0]["id"] == oai, detail)
check("detail members count includes AI", detail.get("members_count") == 3, detail.get("members_count"))

r = call("POST", f"/api/v1/conversations/{cid}/messages", ot, json={"content": "大家中午吃什么"})
check("send group message", r.status_code == 200, r.text)
time.sleep(3)
r = call("GET", f"/api/v1/conversations/{cid}/messages?limit=20", ot)
msgs = r.json().get("messages", [])
ai_msgs = [m for m in msgs if m.get("sender_type") == "ai"]
check("AI replied in group", len(ai_msgs) >= 1, msgs)
check("AI sender is friend", any((m.get("meta") or {}).get("ai_friend_id") == oai for m in ai_msgs), ai_msgs)

r = call("POST", f"/api/v1/conversations/{cid}/members", ft, json={"user_ids": [f2id]})
check("invite second friend", r.status_code == 200 and len(r.json().get("added_users", [])) == 1, r.text)
r = call("GET", f"/api/v1/conversations/{cid}", ot)
check("member count 4", r.json()["conversation"].get("members_count") == 4, r.json()["conversation"].get("members_count"))

r = call("DELETE", f"/api/v1/conversations/{cid}/ai-members/{oai}", ot)
check("remove AI", r.status_code == 200, r.text)
r = call("GET", f"/api/v1/conversations/{cid}", ot)
check("AI removed", len(r.json()["conversation"].get("ai_members", [])) == 0, r.json()["conversation"])
before = len(call("GET", f"/api/v1/conversations/{cid}/messages?limit=50", ot).json().get("messages", []))
call("POST", f"/api/v1/conversations/{cid}/messages", ot, json={"content": "AI 已经不在群里了"})
time.sleep(2)
after_msgs = call("GET", f"/api/v1/conversations/{cid}/messages?limit=50", ot).json().get("messages", [])
check("no AI reply after removal", len(after_msgs) == before + 1, f"{before} -> {len(after_msgs)}")

print(f"\nfailures: {fail}")
sys.exit(1 if fail else 0)
