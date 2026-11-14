"""检查管理面板的专属 Key 弹窗与提供商归属选择。"""
from __future__ import annotations

import os
import sys
from pathlib import Path

from playwright.sync_api import sync_playwright

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8098"
SHOTS = Path(__file__).resolve().parent.parent / "shots"
SHOTS.mkdir(exist_ok=True)
TMP = Path(os.environ.get("TEMP", SHOTS))
errors = []

with sync_playwright() as p:
    browser = p.chromium.launch(channel="msedge", headless=True)
    page = browser.new_page(viewport={"width": 1440, "height": 900})
    page.on("pageerror", lambda exc: errors.append(f"pageerror: {exc}"))
    page.goto(f"{BASE}/admin", wait_until="networkidle")
    page.fill("#login-user", "admin")
    page.fill("#login-pass", "change-me-now")
    page.click("#login-form button[type=submit]")
    page.wait_for_timeout(1500)

    page.goto(f"{BASE}/admin#/users", wait_until="networkidle")
    page.wait_for_timeout(900)
    keys = page.locator("button[data-keys]")
    print("key buttons:", keys.count())
    if keys.count():
        keys.first.click()
        page.wait_for_timeout(800)
        body = page.locator("#modal-root").inner_text()
        print("keys modal:", body[:160].replace("\n", " | "))
        if "专属模型 Key" not in body:
            errors.append("keys modal missing title")
        page.screenshot(path=str(SHOTS / "49-admin-user-keys.png"), full_page=True)
        add = page.locator("#user-key-add")
        if add.count():
            add.click()
            page.wait_for_timeout(700)
            modal = page.locator("#modal-root").inner_text()
            print("provider modal:", modal[:160].replace("\n", " | "))
            if "专属用户" not in modal:
                errors.append("provider modal missing owner select")
            page.screenshot(path=str(SHOTS / "50-admin-owner-select.png"), full_page=True)
            page.keyboard.press("Escape")
    else:
        errors.append("no private key buttons on users page")

    page.goto(f"{BASE}/admin#/providers", wait_until="networkidle")
    page.wait_for_timeout(900)
    edits = page.locator("button[data-edit]")
    if edits.count():
        edits.first.click()
        page.wait_for_timeout(700)
        modal = page.locator("#modal-root").inner_text()
        print("provider edit modal:", modal[:160].replace("\n", " | "))
        if "专属用户" not in modal:
            errors.append("provider edit missing owner select")
    else:
        errors.append("no providers to edit")
    browser.close()

if errors:
    print("\n".join(errors))
    sys.exit(1)
print("KEYS UI OK")
