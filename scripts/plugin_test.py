"""插件热上传测试：构造一个使用相对导入的 AstrBot 插件 zip，上传并验证命令生效。"""
from __future__ import annotations

import io
import sys
import time
import zipfile

import httpx

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8099"
client = httpx.Client(base_url=BASE, timeout=60.0)
fail = 0


def check(name, cond, detail=""):
    global fail
    print(("[PASS] " if cond else "[FAIL] ") + name + ("" if cond else " :: " + str(detail)[:400]))
    if not cond:
        fail += 1


MAIN = '''from astrbot.api.event import filter, AstrMessageEvent
from astrbot.api.star import Context, Star, register
from .helper import GREETING


@register("zip_demo", "NekoMate", "上传测试插件", "1.2.3")
class ZipDemo(Star):
    def __init__(self, context: Context):
        super().__init__(context)

    @filter.command("ziptest", alias={"压缩包测试"})
    async def ziptest(self, event: AstrMessageEvent):
        yield event.plain_result(f"{GREETING}，{event.get_sender_name()}")
'''

HELPER = 'GREETING = "来自 zip 插件的问候"\n'
META = """name: astrbot_plugin_zip_demo
display_name: 压缩包测试插件
desc: 验证 zip 上传与相对导入
author: NekoMate
version: 1.2.3
"""
SCHEMA = '{"greeting_style": {"type": "select", "options": ["正式", "随意"], "default": "随意", "description": "问候风格"}}'

r = client.post("/api/admin/auth/login", json={"username": "admin", "password": "change-me-now"})
admin = r.json()["token"]
AH = {"Authorization": f"Bearer {admin}"}

buf = io.BytesIO()
with zipfile.ZipFile(buf, "w") as z:
    z.writestr("zip_demo/main.py", MAIN)
    z.writestr("zip_demo/helper.py", HELPER)
    z.writestr("zip_demo/metadata.yaml", META)
    z.writestr("zip_demo/_conf_schema.json", SCHEMA)
buf.seek(0)

r = client.post("/api/admin/plugins/upload", headers=AH, files={"file": ("zip_demo.zip", buf.getvalue(), "application/zip")})
check("upload plugin", r.status_code == 200 and r.json().get("status") == "loaded", r.text)

uname = f"zip{int(time.time()) % 100000}"
r = client.post("/api/v1/auth/register", json={"username": uname, "password": "test123456", "nickname": "压缩包"})
token = r.json()["token"]
ai_id = r.json()["ai_friend"]["id"]
H = {"Authorization": f"Bearer {token}"}

r = client.post(f"/api/v1/ai/friends/{ai_id}/chat", headers=H, json={"content": "/ziptest"})
check("zip plugin command", r.status_code == 200 and "zip 插件的问候" in r.json()["reply"]["content"], r.text)

r = client.get("/api/admin/plugins", headers=AH)
row = next((p for p in r.json()["plugins"] if p["slug"] == "zip_demo"), None)
check("plugin listed", row is not None and row["config_schema"].get("greeting_style"), str(row)[:300])

r = client.put("/api/admin/plugins/zip_demo/config", headers=AH, json={"config": {"greeting_style": "正式"}})
check("plugin config save", r.status_code == 200 and r.json()["config"]["greeting_style"] == "正式", r.text)

r = client.post("/api/admin/plugins/zip_demo/disable", headers=AH)
check("plugin disable", r.status_code == 200, r.text)
r = client.post(f"/api/v1/ai/friends/{ai_id}/chat", headers=H, json={"content": "/ziptest"})
check("disabled plugin no longer fires", "zip 插件的问候" not in r.json()["reply"]["content"], r.text[:300])

r = client.post("/api/admin/plugins/zip_demo/enable", headers=AH)
r = client.delete("/api/admin/plugins/zip_demo", headers=AH)
check("plugin delete", r.status_code == 200, r.text)
r = client.get("/api/admin/plugins", headers=AH)
check("plugin gone", not any(p["slug"] == "zip_demo" for p in r.json()["plugins"]), r.text[:300])

print(f"\nfailures: {fail}")
sys.exit(1 if fail else 0)
