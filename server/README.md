# NekoMate Server

FastAPI 服务端，默认监听 `8080`，数据保存在 `NM_DATA_DIR` 下的 SQLite 数据库和上传目录中。

## 目录

```text
server/
├── app/
│   ├── ai/                 # 模型提供商、对话引擎、提示词
│   ├── routers/            # REST API 路由
│   ├── services/           # 媒体、知识库、调度、日志
│   ├── static/admin/       # Web 管理后台
│   ├── config.py
│   ├── database.py
│   ├── models.py
│   └── main.py
├── astrbot/                # AstrBot 兼容层
├── Dockerfile
├── docker-compose.yml
├── entrypoint.sh
└── requirements.txt
```

## 环境变量

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `NM_HOST` | `0.0.0.0` | 监听地址 |
| `NM_PORT` | `8080` | 监听端口 |
| `NM_DATA_DIR` | `./data` | 数据库和上传文件目录 |
| `NM_PUBLIC_BASE_URL` | `http://YOUR_SERVER:8080` | 对外访问地址，用于媒体 URL |
| `NM_SECRET_KEY` | 无 | JWT/签名密钥，生产环境必须设置 |
| `NM_ADMIN_USERNAME` | `admin` | 初始管理员账号 |
| `NM_ADMIN_PASSWORD` | `change-me-now` | 初始管理员密码，首次登录后请修改 |

完整配置见 `.env.example`。

## 启动

```bash
pip install -r requirements.txt
export NM_DATA_DIR=./data
export NM_PORT=8080
uvicorn app.main:app --host 0.0.0.0 --port 8080
```

健康检查：

```bash
curl http://127.0.0.1:8080/api/v1/health
```

## Docker

```bash
docker compose up -d --build
```

服务端需要能够访问配置的模型提供商；如果使用本机 whisper.cpp，Base URL 填写 `http://127.0.0.1:8080/inference`（端口按实际部署修改）。
