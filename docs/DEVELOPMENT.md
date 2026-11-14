# 开发说明

## 服务端

```bash
python -m venv .venv
source .venv/bin/activate
pip install -r server/requirements.txt
python -m compileall -q server/app
uvicorn app.main:app --reload --port 8080
```

管理后台静态文件位于 `server/app/static/admin`，修改后刷新浏览器即可。

## Android

要求：

- JDK 17+
- Android SDK Build-Tools
- PowerShell

```powershell
cd android
.\build.ps1
```

`build.ps1` 使用 `aapt2`、`javac`、`d8`、`zipalign`、`apksigner` 构建纯 Java APK，不依赖 Gradle。首次运行会生成调试 keystore。

## 测试脚本

`scripts/` 下的脚本用于本地 API 和 UI 辅助测试。运行前请根据目标实例设置环境变量或命令行参数，不要在脚本中硬编码真实服务器地址或密钥。

## 代码风格

- Python：PEP 8，类型标注优先。
- Java：保持纯 Java/Android SDK 实现，避免引入不必要的依赖。
- 文档：中文为主，命令和标识符保留原文。
- 提交前运行：

```bash
python -m compileall -q server/app
node --check server/app/static/admin/app.js
```
