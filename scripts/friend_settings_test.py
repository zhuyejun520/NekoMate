import httpx, time

c = httpx.Client(base_url="http://127.0.0.1:8092", timeout=30)
stamp = int(time.time()) % 100000

def reg(n):
    return c.post("/api/v1/auth/register", json={"username": n, "password": "test123456", "nickname": n}).json()

a = reg("fa" + str(stamp))
b = reg("fb" + str(stamp))
ha = {"Authorization": "Bearer " + a["token"]}
hb = {"Authorization": "Bearer " + b["token"]}
print("send req", c.post("/api/v1/friends/requests", headers=ha, json={"username": "fb" + str(stamp), "message": "hi"}).status_code)
reqs = c.get("/api/v1/friends/requests", headers=hb).json()
rid = reqs["incoming"][0]["id"]
print("accept", c.post(f"/api/v1/friends/requests/{rid}/accept", headers=hb).status_code)
fr = c.get("/api/v1/friends", headers=ha).json()["friends"][0]
cid = fr["conversation_id"]
print("friend", fr["id"], cid)
print("msg", c.post(f"/api/v1/conversations/{cid}/messages", headers=ha, json={"content": "hello"}).status_code)
r = c.get(f"/api/v1/conversations/{cid}/settings", headers=ha)
print("settings", r.status_code, r.text[:300])
r = c.put(f"/api/v1/conversations/{cid}/settings", headers=ha, json={"muted": True, "pinned": True})
print("update", r.status_code, r.text)
print("convs pinned", [(x["id"], x.get("pinned"), x.get("muted")) for x in c.get("/api/v1/conversations", headers=ha).json()["conversations"] if x["id"] == cid])
print("clear", c.post(f"/api/v1/conversations/{cid}/clear", headers=ha).status_code)
print("msgs after clear", c.get(f"/api/v1/conversations/{cid}/messages", headers=ha).json())
print("moment", c.post("/api/v1/moments", headers=hb, json={"content": "# B dynamic", "images": []}).status_code)
r = c.get(f"/api/v1/moments?scope=user&user_id={b['user']['id']}", headers=ha)
print("B moments", r.status_code, len(r.json().get("moments", [])))
print("block", c.post(f"/api/v1/friends/{b['user']['id']}/block", headers=ha).status_code)
print("send blocked", c.post(f"/api/v1/conversations/{cid}/messages", headers=ha, json={"content": "after block"}).status_code)
print("friends after block", len(c.get("/api/v1/friends", headers=ha).json()["friends"]))
print("unblock", c.delete(f"/api/v1/friends/{b['user']['id']}/block", headers=ha).status_code)
