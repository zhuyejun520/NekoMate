# 架构说明

```text
Android Client
      │ HTTP / WebSocket
      ▼
FastAPI API ────── Web Admin (static SPA)
      │
      ├── SQLite (users, conversations, messages, personas, memories)
      ├── Uploads (/data/uploads)
      ├── AI Providers (OpenAI-compatible chat / vision / image / STT)
      └── AstrBot-compatible plugins
```

## 核心模块

- `server/app/routers/auth.py`：注册、登录、个人资料。
- `server/app/routers/chat.py`：好友、群聊、消息、撤回、语音消息。
- `server/app/routers/ai.py`：AI 好友、角色卡、记忆、主动消息。
- `server/app/ai/providers.py`：OpenAI 兼容模型调用、GLM 思考过程处理、STT。
- `server/app/ai/engine.py`：对话上下文、记忆写入、主动消息。
- `server/app/static/admin`：Web 管理后台。
- `android/src`：Android 客户端全部 Java 源码。

## 数据流

1. 客户端通过 `/api/v1/auth/login` 获取 JWT。
2. 聊天消息写入 SQLite，同时通过 WebSocket 推送给在线成员。
3. AI 会话由 `CompanionEngine` 组装角色卡、历史、记忆和风格样本。
4. 模型提供商按 `kind` 和优先级选择；STT 优先使用用户私有 STT，其次全局 STT。
5. 上传文件保存在 `NM_DATA_DIR/uploads`，媒体 URL 使用 `NM_PUBLIC_BASE_URL`。

## 兼容性

服务端模型接口采用 OpenAI-compatible `/chat/completions`、`/images/generations`、`/audio/transcriptions`；本地 whisper.cpp 额外支持 `/inference`。
