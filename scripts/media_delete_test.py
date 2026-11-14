import base64, time, httpx

c = httpx.Client(base_url="http://127.0.0.1:8087", timeout=30)
stamp = str(int(time.time()) % 100000)
u = c.post("/api/v1/auth/register", json={
    "username": "media" + stamp, "password": "test123456", "nickname": "media"}).json()
h = {"Authorization": "Bearer " + u["token"]}
ai = u["ai_friend"]
conv = ai["conversation_id"]
png = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/p9sAAAAASUVORK5CYII=")

def upload(name):
    r = c.post("/api/v1/upload", headers=h, files={"file": (name, png, "image/png")})
    print("upload", r.status_code, r.text[:200])
    return r.json()["url"]

url1 = upload("recall.png")
r = c.post(f"/api/v1/conversations/{conv}/messages", headers=h, json={
    "content": "", "content_type": "image", "image_url": url1})
print("send image", r.status_code, r.text[:200])
mid = r.json()["message"]["id"]
print("recall", c.post(f"/api/v1/conversations/{conv}/messages/{mid}/recall", headers=h).status_code)
print("recall media status", c.get(url1).status_code)

url2 = upload("moment.png")
r = c.post("/api/v1/moments", headers=h, json={"content": "media delete", "images": [url2]})
print("post moment", r.status_code, r.text[:100])
mid2 = r.json()["moment"]["id"]
print("delete moment", c.delete(f"/api/v1/moments/{mid2}", headers=h).status_code)
print("moment media status", c.get(url2).status_code)
