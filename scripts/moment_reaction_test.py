import httpx, time

c = httpx.Client(base_url="http://127.0.0.1:8088", timeout=30)
admin = c.post("/api/admin/auth/login", json={"username": "admin", "password": "change-me-now"}).json()["token"]
ha = {"Authorization": "Bearer " + admin}
for kind in ("chat", "vision"):
    c.post("/api/admin/providers", headers=ha, json={
        "name": "mock-" + kind, "kind": kind, "base_url": "http://127.0.0.1:8100/v1",
        "api_key": "x", "model": "mock-chat", "enabled": True, "priority": 1})
stamp = str(int(time.time()) % 100000)
u = c.post("/api/v1/auth/register", json={
    "username": "moment" + stamp, "password": "test123456", "nickname": "moment"}).json()
h = {"Authorization": "Bearer " + u["token"]}
ai = u["ai_friend"]
ai_id = ai["id"]
settings = ai.get("settings") or {}
settings["affinity"] = 100
settings["affinity_enabled"] = True
print("update ai", c.put(f"/api/v1/ai/friends/{ai_id}", headers=h, json={"settings": settings}).status_code)
for i in range(5):
    r = c.post("/api/v1/moments", headers=h, json={"content": f"今天测试动态 {i}", "images": []})
    print("post", i, r.status_code)
time.sleep(4)
r = c.get("/api/v1/moments", headers=h)
for m in r.json().get("moments", []):
    print("moment", m["id"], "likes", m.get("likes"), "comments", [(x.get("user", {}).get("nickname"), x.get("content")) for x in m.get("comments", [])])
