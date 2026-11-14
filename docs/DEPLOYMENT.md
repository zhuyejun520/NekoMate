# 部署说明

## Docker Compose

```bash
cd server
cp .env.example .env
# 修改 .env 中的 NM_PUBLIC_BASE_URL、NM_SECRET_KEY 和管理员密码
docker compose up -d --build
```

## 直接运行

```bash
python -m venv .venv
source .venv/bin/activate
pip install -r server/requirements.txt
export NM_DATA_DIR=/path/to/data
export NM_PORT=8080
uvicorn app.main:app --host 0.0.0.0 --port 8080
```

## 反向代理

建议在前面放 Nginx/Caddy，启用 HTTPS，并只暴露必要端口。

```nginx
server {
    listen 443 ssl;
    server_name example.com;

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    location /ws {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
    }
}
```

## 数据备份

需要备份：

- `NM_DATA_DIR/nekomate.db`
- `NM_DATA_DIR/uploads/`
- `NM_DATA_DIR/knowledge/`
- `NM_DATA_DIR/plugins/`

## 模型服务

- 云端模型：在管理后台添加 OpenAI 兼容提供商。
- 本地 whisper.cpp：STT 提供商 Base URL 填 `http://127.0.0.1:8080/inference`，模型名填 `tiny` / `small`。
- 生产环境请限制模型 Key 权限，不要提交 `.env` 或真实 Key。

## 客户端

客户端登录页填写实际服务器地址，例如：

```text
http://YOUR_SERVER:8080
```
