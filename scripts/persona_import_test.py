import httpx, time

c = httpx.Client(base_url="http://127.0.0.1:8089", timeout=30)
admin = c.post("/api/admin/auth/login", json={"username": "admin", "password": "change-me-now"}).json()["token"]
h = {"Authorization": "Bearer " + admin}
stamp = str(int(time.time()) % 100000)
cards = [
    {"name": "导入角色A" + stamp, "tagline": "tag", "description": "背景", "personality": "温柔", "first_mes": "你好", "catchphrases": "诶, 嘛"},
    {"char_name": "导入角色B" + stamp, "description": "背景B", "avatar": "🦊"},
    {"name": ""},
]
r = c.post("/api/admin/personas/import", headers=h, json=cards)
print("array", r.status_code, r.text[:500])
r2 = c.post("/api/admin/personas/import", headers=h, json={"characters": [{"name": "导入角色C" + stamp, "description": "C"}]})
print("wrapper", r2.status_code, r2.text[:300])
r3 = c.post("/api/admin/personas/import", headers=h, json={"personas": [{"name": "导入角色A" + stamp}]})
print("duplicate", r3.status_code, r3.text[:300])
print("list", len(c.get("/api/admin/personas", headers=h).json()["personas"]))
