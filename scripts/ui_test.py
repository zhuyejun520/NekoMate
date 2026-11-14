"""管理面板 UI 冒烟测试：登录、逐页检查 JS 控制台错误并截图。"""
from __future__ import annotations

import sys
import time
from pathlib import Path

from playwright.sync_api import sync_playwright

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8099"
SHOTS = Path(__file__).resolve().parent.parent / "shots"
SHOTS.mkdir(exist_ok=True)

errors: list[str] = []
pages = {
    "welcome": "开箱检查清单",
    "personas": "角色卡",
    "knowledge": "知识库",
    "providers": "快速添加",
    "users": "账号列表",
    "plugins": "插件管理",
    "tasks": "新建任务",
    "releases": "上传新版本",
    "logs": "日志",
    "settings": "全局设置",
}


with sync_playwright() as p:
    browser = p.chromium.launch(channel="msedge", headless=True)
    ctx = browser.new_context(viewport={"width": 1440, "height": 900}, device_scale_factor=1)
    page = ctx.new_page()
    page.on("console", lambda msg: errors.append(f"console.{msg.type}: {msg.text}") if msg.type in ("error",) else None)
    page.on("pageerror", lambda exc: errors.append(f"pageerror: {exc}"))

    page.goto(f"{BASE}/admin", wait_until="networkidle")
    page.wait_for_timeout(600)
    page.screenshot(path=str(SHOTS / "01-login.png"), full_page=True)

    page.fill("#login-user", "admin")
    page.fill("#login-pass", "change-me-now")
    page.click("#login-form button[type=submit]")
    page.wait_for_timeout(1800)
    assert page.locator("#app-view:not(.hidden)").count() == 1, "login failed"
    page.screenshot(path=str(SHOTS / "02-welcome.png"), full_page=True)

    for name, marker in pages.items():
        page.goto(f"{BASE}/admin#/{name}", wait_until="networkidle")
        page.wait_for_timeout(900)
        page.screenshot(path=str(SHOTS / f"03-{name}.png"), full_page=True)
        body = page.locator("#page").inner_text()
        print(f"[page] {name}: {len(body)} chars")
        if marker not in body:
            errors.append(f"page {name} missing marker {marker}: {body[:160]}")
        if any(token in body for token in ("页面加载失败", "Cannot read", "TypeError", "undefined")):
            errors.append(f"page {name} errored: {body[:200]}")

    # 知识库详情页
    page.goto(f"{BASE}/admin#/knowledge", wait_until="networkidle")
    page.wait_for_timeout(700)
    links = page.locator("a[href^='#/knowledge/']")
    if links.count():
        links.first.click()
        page.wait_for_timeout(1200)
        page.screenshot(path=str(SHOTS / "04-knowledge-detail.png"), full_page=True)

    browser.close()

if errors:
    print("\n".join(errors))
    print(f"UI errors: {len(errors)}")
    sys.exit(1)
print("UI OK")
