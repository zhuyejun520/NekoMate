import httpx, time

c = httpx.Client(base_url="http://127.0.0.1:8090", timeout=30)
# admin seeded
admin = c.post("/api/admin/auth/login", json={"username": "admin", "password": "change-me-now"}).json()["token"]
ha_admin = {"Authorization": "Bearer " + admin}
# regular
stamp = int(time.time()) % 100000
u = c.post("/api/v1/auth/register", json={"username": "world" + str(stamp), "password": "test123456", "nickname": "world"}).json()
h = {"Authorization": "Bearer " + u["token"]}
print("world", c.get("/api/v1/ai/personas/world", headers=h).status_code)
print("mine", c.get("/api/v1/ai/personas/mine", headers=h).status_code)
print("save key", c.put("/api/v1/ai/private-key", headers=h, json={
    "base_url": "https://example.com/v1", "api_key": "sk-world-123456", "model": "m"}).status_code)
p = c.post("/api/v1/ai/personas", headers=h, json={"name": "world-card", "tagline": "t", "description": "d"}).json()["persona"]
print("private", p["id"])
r = c.post(f"/api/v1/ai/personas/{p['id']}/submit", headers=h)
print("submit", r.status_code, r.text[:300])
pending = c.get("/api/v1/ai/personas/pending", headers=ha_admin)
print("pending", pending.status_code, len(pending.json().get("personas", [])))
if pending.json().get("personas"):
    pid = pending.json()["personas"][0]["id"]
    print("approve", c.post(f"/api/v1/ai/personas/{pid}/approve", headers=ha_admin).status_code)
    print("world after", c.get("/api/v1/ai/personas/world", headers=h).json()["total"])
    print("admin public", c.get("/api/v1/ai/personas/admin/public", headers=ha_admin).status_code)
    print("admin edit", c.put(f"/api/v1/ai/personas/{pid}/admin", headers=ha_admin,
                              json={"name": "edited-name", "avatar": "x"}).status_code)
    print("delete", c.delete(f"/api/v1/ai/personas/{pid}/admin", headers=ha_admin).status_code)
