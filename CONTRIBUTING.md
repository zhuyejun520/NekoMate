# 贡献说明

1. Fork 仓库并创建分支。
2. 保持改动聚焦，避免同时提交无关的重构。
3. Python 代码提交前运行：

   ```bash
   python -m compileall -q server/app
   node --check server/app/static/admin/app.js
   ```

4. Android 改动请在本地执行 `android/build.ps1` 验证构建。
5. 不要提交真实服务器地址、API Key、密码、数据库或签名文件。
6. 提交 Pull Request 时说明改动目的、影响范围和测试方式。
