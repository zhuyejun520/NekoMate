# NekoMate

NekoMate 是一套 AI 陪伴聊天系统，包含：

- **FastAPI 服务端**：账号、好友、群聊、AI 好友、角色卡、动态、知识库、记忆、主动消息、AstrBot 兼容插件。
- **Web 管理后台**：模型提供商、用户、角色卡、插件、日志和系统设置。
- **Android 客户端**：纯 Java 实现，包含文字、图片、表情、语音、AI 聊天和 root 通知支持。

## 仓库结构

```text
.
├── server/                 # FastAPI 服务端
│   ├── app/                # 应用代码、管理后台静态资源
│   ├── astrbot/            # AstrBot 兼容层
│   ├── Dockerfile
│   └── requirements.txt
├── android/                # Android 客户端源码
│   ├── src/
│   ├── res/
│   ├── AndroidManifest.xml
│   └── build.ps1
├── scripts/                # 本地开发和接口测试脚本
├── docs/                   # 架构、部署、开发和更新记录
└── .github/workflows/      # CI
```

## 本地运行服务端

```bash
cd server
python -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
export NM_DATA_DIR=./data
export NM_PORT=8080
uvicorn app.main:app --host 0.0.0.0 --port 8080
```

Windows PowerShell：

```powershell
cd server
python -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -r requirements.txt
$env:NM_DATA_DIR = ".\data"
$env:NM_PORT = "8080"
uvicorn app.main:app --host 0.0.0.0 --port 8080
```

管理后台：

```text
http://YOUR_SERVER:8080/admin
```

API 文档：

```text
http://YOUR_SERVER:8080/docs
```

## 配置

源码中的服务器地址统一使用 `YOUR_SERVER` 占位符。部署前请修改：

- `android/src/com/nekocyte/companion/Session.java`
- `android/res/layout/activity_login.xml`
- `server/app/config.py`
- `server/docker-compose.yml`
- `server/.env.example`

也可以在客户端登录页直接填写实际服务器地址。

## Android 构建

需要：

- JDK 17 或更高
- Android SDK Build-Tools（`aapt2`、`d8`、`zipalign`、`apksigner`）
- PowerShell

```powershell
cd android
.\build.ps1
```

首次构建会在 `android/keystore/` 生成调试签名，该目录已被 `.gitignore` 排除。

## Docker

```bash
cd server
docker compose up -d --build
```

默认数据目录为 `/data`，建议挂载持久化卷。

## 文档

- [架构说明](docs/ARCHITECTURE.md)
- [部署说明](docs/DEPLOYMENT.md)
- [开发说明](docs/DEVELOPMENT.md)
- [更新记录](docs/CHANGELOG.md)
- [源码导出说明](docs/SOURCE_NOTICE.md)

## 许可证

本项目使用 **GNU General Public License v3.0**。

完整许可证文本见 [LICENSE](LICENSE)。

```text
SPDX-License-Identifier: GPL-3.0-only
```
