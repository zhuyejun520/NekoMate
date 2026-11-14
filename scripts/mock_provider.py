"""本地 mock 模型服务，用于验证 OpenAI 兼容协议链路（不联网）。"""
from __future__ import annotations

import json
import re
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):  # noqa: D401
        sys.stderr.write("[mock] " + fmt % args + "\n")

    def _send(self, payload: dict, status: int = 200):
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path.endswith("/models"):
            return self._send({"object": "list", "data": [{"id": "mock-chat", "object": "model"}]})
        return self._send({"error": "not found"}, 404)

    def do_POST(self):
        length = int(self.headers.get("Content-Length") or 0)
        try:
            body = json.loads(self.rfile.read(length) or b"{}")
        except Exception:
            body = {}
        if self.path.rstrip("/").endswith("/images/generations"):
            prompt = body.get("prompt", "")
            return self._send({"created": 1, "data": [{"url": f"https://mock.local/image.png?p={abs(hash(prompt)) % 1000}"}]})

        messages = body.get("messages") or []
        system = messages[0].get("content", "") if messages else ""
        last_user = ""
        has_image = False
        for m in reversed(messages):
            if m.get("role") == "user":
                content = m.get("content", "")
                if isinstance(content, list):
                    has_image = any(
                        isinstance(x, dict) and x.get("type") == "image_url" for x in content
                    )
                    last_user = " ".join(
                        str(x.get("text", "")) for x in content if isinstance(x, dict)
                    )
                else:
                    last_user = str(content)
                break

        if has_image:
            return self._send(
                {
                    "id": "chatcmpl-mock",
                    "object": "chat.completion",
                    "model": body.get("model", "mock"),
                    "choices": [{"index": 0, "message": {"role": "assistant", "content": "图片里是一张桌面照片，桌上有一杯冰美式和一本翻开的书，光线偏暖。"}, "finish_reason": "stop"}],
                    "usage": {"prompt_tokens": 10, "completion_tokens": 10, "total_tokens": 20},
                }
            )

        if body.get("response_format", {}).get("type") == "json_object" or "记忆筛选器" in system:
            summary = re.sub(r"\s+", " ", last_user)[:40]
            delta = 0
            if "喜欢你" in last_user or "谢谢你" in last_user:
                delta = 3
            elif "讨厌" in last_user or "滚" in last_user:
                delta = -3
            payload = json.dumps(
                {
                    "important": True,
                    "importance": 0.92,
                    "summary": f"用户提到：{summary}",
                    "facts": [f"关于用户的关键信息：{summary}"],
                    "keywords": ["冰美式", "考研"],
                    "affinity_delta": delta,
                },
                ensure_ascii=False,
            )
            return self._send(
                {
                    "id": "chatcmpl-mock",
                    "object": "chat.completion",
                    "model": body.get("model", "mock"),
                    "choices": [{"index": 0, "message": {"role": "assistant", "content": payload}, "finish_reason": "stop"}],
                    "usage": {"prompt_tokens": 10, "completion_tokens": 10, "total_tokens": 20},
                }
            )

        reply = f"（mock）收到：{last_user[:40]}。今天也要好好吃饭呀。"
        return self._send(
            {
                "id": "chatcmpl-mock",
                "object": "chat.completion",
                "model": body.get("model", "mock"),
                "choices": [{"index": 0, "message": {"role": "assistant", "content": reply}, "finish_reason": "stop"}],
                "usage": {"prompt_tokens": 10, "completion_tokens": 10, "total_tokens": 20},
            }
        )


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8100
    server = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    print(f"mock provider listening on http://127.0.0.1:{port}/v1", flush=True)
    server.serve_forever()
