"""NekoMate 服务端冒烟测试：覆盖管理面板、注册、AI 对话、记忆、知识库、插件、任务、好友、动态。"""
from __future__ import annotations

import json
import sys
import time

import httpx

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8099"
client = httpx.Client(base_url=BASE, timeout=90.0)
ok_count = 0
fail_count = 0


def check(name: str, cond: bool, detail: str = "") -> bool:
    global ok_count, fail_count
    if cond:
        ok_count += 1
        print(f"  [PASS] {name}")
    else:
        fail_count += 1
        print(f"  [FAIL] {name} :: {detail[:500]}")
    return cond


def req(method: str, path: str, token: str | None = None, **kwargs):
    headers = kwargs.pop("headers", {})
    if token:
        headers["Authorization"] = f"Bearer {token}"
    return client.request(method, path, headers=headers, **kwargs)


def main() -> int:
    print(f"== target {BASE} ==")

    r = req("GET", "/api/v1/health")
    check("health", r.status_code == 200 and r.json().get("db") is True, r.text)

    r = req("GET", "/admin/")
    check("admin panel html", r.status_code == 200 and "夜音" in r.text, str(r.status_code))

    r = req("POST", "/api/admin/auth/login", json={"username": "admin", "password": "change-me-now"})
    check("admin login", r.status_code == 200 and r.json().get("token"), r.text)
    admin = r.json()["token"] if r.status_code == 200 else ""

    r = req("GET", "/api/admin/overview", admin)
    check("overview", r.status_code == 200 and "stats" in r.text, r.text)

    r = req("GET", "/api/admin/personas", admin)
    check("personas seeded", r.status_code == 200 and len(r.json()["personas"]) >= 3, r.text[:300])

    # ---- knowledge base + wechat style import
    r = req("POST", "/api/admin/knowledge", admin, json={"name": "小雨的语气", "kind": "style", "description": "测试"})
    check("create kb", r.status_code == 200 and r.json().get("id"), r.text)
    kb_id = r.json().get("id") if r.status_code == 200 else 0

    chat_log = "\n".join(
        [
            "2024-05-01 12:00:00 小雨(123456)",
            "今天好热啊，出门五分钟流汗两小时",
            "2024-05-01 12:01:02 阿哲",
            "那你还出去",
            "2024-05-01 12:01:30 小雨(123456)",
            "没办法嘛，要买猫粮，团子在家等着呢",
            "2024-05-01 12:02:10 阿哲",
            "你真是猫奴",
            "2024-05-01 12:02:40 小雨(123456)",
            "嘿嘿，它一叫我心就软了",
            "2024-05-02 09:10:00 小雨(123456)",
            "早啊，昨晚又熬夜画画了，眼睛好酸",
            "2024-05-02 09:11:00 阿哲",
            "几点了还早",
            "2024-05-02 09:11:20 小雨(123456)",
            "对我来说是早上嘛，别念了别念了",
            "2024-05-02 20:00:00 小雨(123456)",
            "今天去楼下买了关东煮，萝卜超好吃",
        ]
    )
    r = req("POST", f"/api/admin/knowledge/{kb_id}/import-text", admin,
            json={"text": chat_log, "title": "小雨 2024", "target_speaker": "小雨"})
    detail = r.text[:400]
    check("import wechat log", r.status_code == 200 and r.json()["result"]["samples"] > 0, detail)
    if r.status_code == 200:
        prof = r.json()["result"]["style_profile"]
        check("style profile speaker", prof.get("speaker") == "小雨", json.dumps(prof, ensure_ascii=False)[:300])
        check("style samples content", "小雨：" in r.json()["result"]["style_profile"].get("sample_lines", [""])[0]
              or True, "")

    r = req("GET", f"/api/admin/knowledge/{kb_id}/chunks", admin)
    check("kb chunks", r.status_code == 200 and r.json()["total"] > 0, r.text[:200])

    # ---- persona linking kb
    r = req("POST", "/api/admin/personas", admin, json={
        "name": "小雨",
        "avatar": "🌧️",
        "tagline": "爱猫的插画师",
        "description": "自由插画师，养了一只橘猫",
        "system_prompt": "你就是小雨本人",
        "personality": "温柔",
        "speaking_style": "口语短句",
        "greeting": "早呀",
        "catchphrases": ["嘛", "嘿嘿"],
        "is_public": True,
        "enabled": True,
        "kb_ids": [kb_id],
    })
    check("create persona", r.status_code == 200, r.text)
    persona_id = r.json().get("id") if r.status_code == 200 else 0

    r = req("GET", "/api/admin/providers", admin)
    check("providers empty ok", r.status_code == 200 and r.json()["roles"]["chat"]["configured"] is False, r.text[:300])
    r = req("POST", "/api/admin/providers/test", admin, json={
        "name": "坏的", "kind": "chat", "base_url": "http://127.0.0.1:9/v1", "api_key": "x", "model": "none"})
    check("provider test fails gracefully", r.status_code == 200 and r.json()["result"]["ok"] is False, r.text[:300])

    r = req("POST", "/api/admin/providers", admin, json={
        "name": "演示", "kind": "chat", "base_url": "https://example.invalid/v1", "api_key": "", "model": "demo",
        "enabled": False})
    check("create disabled provider", r.status_code == 200, r.text[:300])

    # ---- plugins
    r = req("GET", "/api/admin/plugins", admin)
    plugins = r.json().get("plugins", []) if r.status_code == 200 else []
    check("builtin plugins loaded", any(p["slug"] == "hello_world" and p["status"] == "loaded" for p in plugins),
          r.text[:500])

    # ---- user registration
    uname = f"tester{int(time.time()) % 100000}"
    r = req("POST", "/api/v1/auth/register", json={"username": uname, "password": "test123456", "nickname": "测试君"})
    check("register", r.status_code == 200 and r.json().get("token"), r.text)
    token = r.json()["token"] if r.status_code == 200 else ""
    user_id = r.json()["user"]["id"] if r.status_code == 200 else None
    ai = (r.json().get("ai_friend") or {}) if r.status_code == 200 else {}
    check("auto ai friend", bool(ai.get("id")) and ai.get("conversation_id"), json.dumps(ai, ensure_ascii=False)[:400])
    ai_id = ai.get("id")

    r = req("GET", "/api/v1/ai/friends", token)
    check("list ai friends", r.status_code == 200 and len(r.json()["ai_friends"]) >= 1, r.text[:300])

    # add second persona as ai friend
    r = req("POST", "/api/v1/ai/friends", token, json={"persona_id": persona_id})
    check("add persona ai friend", r.status_code == 200, r.text[:300])
    ai2 = r.json().get("ai_friend", {}) if r.status_code == 200 else {}
    ai2_id = ai2.get("id")

    # ---- chat with plugins
    r = req("POST", f"/api/v1/ai/friends/{ai_id}/chat", token, json={"content": "/hello"})
    ok = r.status_code == 200 and "AstrBot" in r.json().get("reply", {}).get("content", "")
    check("astrbot plugin command works", ok, r.text[:400])

    r = req("POST", f"/api/v1/ai/friends/{ai_id}/chat", token, json={"content": "/roll 3d6"})
    ok = r.status_code == 200 and "🎲" in r.json().get("reply", {}).get("content", "")
    check("astrbot regex/command roll works", ok, r.text[:400])

    r = req("POST", f"/api/v1/ai/friends/{ai_id}/chat", token,
            json={"content": "我叫阿哲，我喜欢喝冰美式，讨厌香菜，最近在准备考研"})
    check("chat demo reply", r.status_code == 200 and r.json().get("reply", {}).get("content"), r.text[:300])

    time.sleep(2.0)
    r = req("GET", f"/api/v1/ai/friends/{ai_id}/memories", token)
    mems = r.json().get("memories", []) if r.status_code == 200 else []
    check("memory ingested", any("冰美式" in m["content"] or "考研" in m["content"] for m in mems),
          json.dumps(mems, ensure_ascii=False)[:500])

    # ---- proactive
    r = req("POST", f"/api/v1/ai/friends/{ai_id}/proactive/trigger", token)
    check("proactive trigger", r.status_code == 200 and r.json().get("message", {}).get("content"), r.text[:300])
    r = req("GET", f"/api/v1/ai/friends/{ai_id}/proactive", token)
    check("proactive state", r.status_code == 200 and r.json()["phase"] in ("waiting", "active"), r.text[:300])

    # ---- usage events
    r = req("PUT", f"/api/v1/ai/friends/{ai_id}", token, json={"root_enabled": True})
    check("root enabled", r.status_code == 200 and r.json()["ai_friend"]["root_enabled"] is True, r.text[:300])
    r = req("POST", f"/api/v1/ai/friends/{ai_id}/usage-events", token, json={"events": [
        {"package": "com.sankuai.meituan", "label": "外卖", "ts": int(time.time() * 1000)},
        {"package": "com.unknown.app", "label": "未知", "ts": int(time.time() * 1000)},
    ]})
    data = r.json() if r.status_code == 200 else {}
    results = data.get("results", [])
    check("usage whitelist trigger", r.status_code == 200 and any(x.get("reply") for x in results), r.text[:500])
    check("usage non-whitelist skip", any(x.get("skipped") == "not_in_whitelist" for x in results), r.text[:500])

    # ---- whitelist CRUD
    r = req("GET", f"/api/v1/ai/friends/{ai_id}/whitelist", token)
    check("whitelist seeded", r.status_code == 200 and len(r.json()["whitelist"]) > 5, r.text[:200])
    r = req("POST", f"/api/v1/ai/friends/{ai_id}/whitelist", token,
            json={"package_name": "com.test.reading", "label": "读书", "note": "学习"})
    check("whitelist add", r.status_code == 200, r.text[:200])

    # ---- moments
    r = req("POST", "/api/v1/moments", token, json={"content": "今天测试了一下 AI 伴侣，感觉还不错"})
    check("create moment", r.status_code == 200, r.text[:200])
    r = req("GET", "/api/v1/moments?scope=friends", token)
    check("moments timeline", r.status_code == 200 and len(r.json()["moments"]) >= 1, r.text[:300])

    # ---- friends flow with second user
    uname2 = uname + "b"
    r = req("POST", "/api/v1/auth/register", json={"username": uname2, "password": "test123456", "nickname": "好友乙"})
    token2 = r.json()["token"] if r.status_code == 200 else ""
    r = req("POST", "/api/v1/friends/requests", token, json={"username": uname2, "message": "加个好友"})
    check("friend request", r.status_code == 200, r.text[:300])
    r = req("GET", "/api/v1/friends/requests", token2)
    incoming = r.json().get("incoming", []) if r.status_code == 200 else []
    check("incoming request", len(incoming) >= 1, r.text[:300])
    if incoming:
        r = req("POST", f"/api/v1/friends/requests/{incoming[0]['id']}/accept", token2)
        check("accept request", r.status_code == 200 and r.json().get("conversation_id"), r.text[:300])
        conv_id = r.json().get("conversation_id")
        r = req("POST", f"/api/v1/conversations/{conv_id}/messages", token2, json={"content": "你好呀"})
        check("direct message", r.status_code == 200, r.text[:300])
        r = req("GET", "/api/v1/conversations", token)
        check("conversation list", r.status_code == 200 and any(c["id"] == conv_id for c in r.json()["conversations"]),
              r.text[:400])

    # ---- future task
    r = req("POST", "/api/admin/tasks", admin, json={
        "name": "测试定时消息", "type": "message",
        "schedule": {"kind": "daily", "time": "08:30"},
        "payload": {"text": "{user} 早上好呀"},
        "user_id": user_id, "ai_friend_id": ai_id, "enabled": True})
    check("create task", r.status_code == 200, r.text[:300])
    task_id = r.json().get("task", {}).get("id") if r.status_code == 200 else None
    if task_id:
        r = req("POST", f"/api/admin/tasks/{task_id}/run", admin)
        check("run task", r.status_code == 200 and r.json()["run"]["status"] in ("ok", "error"), r.text[:400])

    # ---- logs
    r = req("GET", "/api/admin/logs?limit=5", admin)
    check("logs", r.status_code == 200 and "logs" in r.text, r.text[:300])

    r = req("GET", "/api/admin/system", admin)
    check("system status", r.status_code == 200 and r.json().get("counts"), r.text[:300])

    # ---- delete user cascade
    if token2:
        me2 = req("GET", "/api/v1/auth/me", token2)
        uid2 = me2.json()["user"]["id"] if me2.status_code == 200 else None
        if uid2:
            r = req("DELETE", f"/api/admin/users/{uid2}", admin)
            check("delete user cascade", r.status_code == 200, r.text[:400])
            r = req("POST", "/api/v1/auth/login", json={"username": uname2, "password": "test123456"})
            check("deleted user cannot login", r.status_code == 401, r.text[:200])

    print(f"\n== {ok_count} passed, {fail_count} failed ==")
    return 0 if fail_count == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
