import httpx, time

c = httpx.Client(base_url="http://127.0.0.1:8091", timeout=30)
stamp = int(time.time()) % 100000
a = c.post("/api/v1/auth/register", json={"username": "pk" + str(stamp), "password": "test123456", "nickname": "pk"}).json()
b = c.post("/api/v1/auth/register", json={"username": "pk2" + str(stamp), "password": "test123456", "nickname": "pk2"}).json()
ha = {"Authorization": "Bearer " + a["token"]}
hb = {"Authorization": "Bearer " + b["token"]}
print("key empty", c.get("/api/v1/ai/private-key", headers=ha).status_code)
r = c.put("/api/v1/ai/private-key", headers=ha, json={"name": "mine", "base_url": "https://example.com/v1", "api_key": "sk-test-1234567890", "model": "test-model"})
print("save key", r.status_code, r.text[:250])
print("key get", c.get("/api/v1/ai/private-key", headers=ha).text[:250])
p = c.post("/api/v1/ai/personas", headers=ha, json={"name": "private", "avatar": "🦊"}).json()["persona"]
print("persona", p["id"])
print("avatar", c.put(f"/api/v1/ai/personas/{p['id']}/avatar", headers=ha, json={"avatar": "/media/test.png"}).status_code)
# friend conversation
c.post("/api/v1/friends/requests", headers=ha, json={"username": "pk2" + str(stamp), "message": "hi"})
reqs = c.get("/api/v1/friends/requests", headers=hb).json()
rid = reqs["incoming"][0]["id"]
c.post(f"/api/v1/friends/requests/{rid}/accept", headers=hb)
fr = c.get("/api/v1/friends", headers=ha).json()["friends"][0]
cid = fr["conversation_id"]
r = c.post(f"/api/v1/conversations/{cid}/messages", headers=ha, json={"content": "recall me"})
mid = r.json()["message"]["id"]
print("message", r.status_code, mid)
r = c.post(f"/api/v1/conversations/{cid}/messages/{mid}/recall", headers=ha)
print("recall", r.status_code, r.text[:250])
print("messages", c.get(f"/api/v1/conversations/{cid}/messages", headers=hb).json())
