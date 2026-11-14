# 夜音 NekoMate · AI 伴侣（服务端）

一套完整的 AI 伴侣后端：账号体系、好友 / 群聊 / 日常空间、AI 好友、角色卡、知识库语气学习、
记忆系统、主动消息、root 应用事件、AstrBot 兼容插件、未来任务，以及自带欢迎界面的 Web 管理面板。

- 服务端：Docker 镜像 `nekomate-server:1.9.18`
- 数据：SQLite（`/data/nekomate.db`），全部账号与聊天数据存服务端
- 管理面板：`http://服务器地址:端口/admin`
- API 文档：`http://服务器地址:端口/docs`

> 客户端（Android，含 root 白名单上报）已同步完成：`android/dist/NekoMate-2.3.17.apk`，
> 默认服务器 `http://YOUR_SERVER:8080`，使用说明见 `android/README.md`。

---

## v2.3.17 表情面板均分

- emoji 7 列、图片表情 4 列等宽居中。
- 客户端 2.3.17。

---

## v2.3.16 取消资料审核

- 个性签名、标签保存不再 AI 审核。
- 客户端 2.3.16，服务端 1.9.18。

---

## v2.3.15 群聊头像修复

- 按发送者显示群成员头像，不再全部使用同一个群头像。
- 客户端 2.3.15，服务端 1.9.17。

---

## v1.9.16 GLM 思考过程修复

- 不再输出 reasoning_content；GLM-5.x 使用 thinking.low 和更大输出预算。
- 客户端仍为 2.3.14。

---

## v2.3.14 角色卡头像同步

- 公共/私人角色卡改头像后同步已添加的 AI 好友；聊天返回时刷新头像。
- 客户端 2.3.14，服务端 1.9.14。

---

## v2.3.13 small 模型超时适配

- whisper.cpp 切换 small；客户端读超时 120 秒。
- 客户端 2.3.13。

---

## v2.3.12 语音转写 UI

- 转写结果显示在语音气泡下，长按复制。
- 录音/发送/播放提示。
- 客户端 2.3.12。

---

## v1.9.13 STT 私有 Key 修复

- 私有 chat Key 用户回退到全局 STT，修复客户端 404。
- 客户端仍为 2.3.11。

---

## v2.3.11 调试日志滚动修复

- 导出仅保留最近 4000 行；启动/2MB 自动滚动；可手动清除。
- 仅客户端更新。

---

## v2.3.10 whisper.cpp 中文优化

- whisper.cpp 请求默认 language=zh，探测音频 1.2 秒，客户端最短 1 秒。
- 服务端 1.9.10。

---

## v2.3.9 STT 配置与语音按钮位置

- 支持本地 whisper.cpp `/inference`（Base URL 必须带 `/inference`）。
- 支持 BigModel GLM-ASR 和 OpenAI Whisper。
- 安卓语音按钮移到输入框左侧。

- Web 控制台可配置语音转写模型（GLM-ASR / Whisper）。
- 安卓语音按钮移到输入框左侧。
- 服务端 1.9.7。

---

## v2.3.8 语音转写修复

- STT 优先使用 BigModel GLM-ASR，修复 DeepSeek 404。
- 录音改为 16k 单声道 WAV；服务端 1.9.6。

---

## v2.3.7 语音消息

- 长按录音、上传、离线缓存、点击播放。
- 长按自己的语音可撤回并删除源文件，任意语音可转文本。
- 服务端 1.9.5 新增语音转写与 audio 消息。

---

## v2.3.6 发送/工具栏按钮切换

- 空输入显示＋，有内容切换发送。
- 输入框顺序调整。
- 仅客户端更新。

---

## v2.3.5 表情面板重写

- 聊天表情改为内嵌面板，顶部搜索 / 黄脸 / 常用图片三个线条图标。
- 默认黄脸 emoji，7 列；图片表情 4 列；搜索支持 emoji 和服务端贴纸。
- 面板展开时表情按钮切换为键盘图标。
- 仅客户端更新。

---

## v2.3.4 Root 通知增强（可选）

- 设置中新增 Root 通知增强开关，默认关闭。
- 开启后 AI 消息通过 su 启动应用前台服务，由应用通知通道发布；失败回退普通通知。
- 同时尝试 deviceidle 白名单与 RUN_IN_BACKGROUND。
- 测试 APK：test-2.4.0-root / versionCode 9001，独立测试版本号。
- 正式版 2.3.5 / versionCode 34。

---

## v2.3.3 我的角色卡布局

- 投稿按钮移至右侧并改名「投稿」。
- 「添加」按钮在投稿上方。
- 顶部标签单行显示。
- 仅客户端更新。

---

## v2.3.2 动态图片网格

- 动态图片最多 9 张，每 3 张换行，9 张为 3×3。
- 仅客户端更新。

---

## v2.3.1 聊天自动滚动

- AI 回复、发送、轮询新增消息后自动滚到底部。
- ListView 开启 transcriptMode，并在布局完成后二次校正。
- 仅客户端更新。

---

## v2.3 图片离线缓存与源文件清理

- 统一图片内存 + 磁盘缓存，聊天/动态/表情包不再反复闪烁。
- 图片预取，离线可看。
- 撤回图片、删除带图动态时清理服务端源文件，共享引用保留。
- 服务端 1.9.4，客户端 2.3.0。

---

## v2.2.4 应用内相机

- OnePlus 系统相机不写临时文件也不再依赖，改用应用内相机。
- 工具栏 2×4，文件第三位，图标略微放大。
- 仅客户端更新。

---

## v2.2.3 相机空图修复

- 发送前校验图片字节数、尺寸并实际解码，损坏文件不上传。
- 增加 `sendImage` 和 `ImageLoader` 图片诊断日志。
- 工具栏固定 2×4，空位用透明控件补齐。
- 仅客户端更新。

---

## v2.2.2 相机兼容修复

- 日志确认 OnePlus 相机返回 canceled 且未写 MediaStore pending 文件。
- 改用自建 `CameraFileProvider` 临时文件 URI。
- 输出文件、缩略图、相册新照片三级兜底。
- 仅客户端更新。

---

## v2.2.1 相机修复

- 修复系统相机拍照后不发送：持久化输出 URI、处理 canceled 结果、onResume 兜底、缩略图回退。
- 移除麦克风、语音/视频通话、位置、红包、转账图标。
- 工具栏仅保留照片、拍摄、文件。
- 底栏与工具栏图标缩小。
- 仅客户端更新。

---

## v2.2 聊天界面重做

- 底栏：麦克风、输入框、发送、表情包、工具栏；工具栏默认收起。
- 相册收进工具栏；工具栏含照片、拍摄、通话、位置、文件、红包、转账。
- 全部图标改为简约线条，用户消息右侧加头像，对方消息左侧加头像。
- 拍摄接入系统相机，首次使用申请相机权限。
- 仅客户端更新；服务端继续使用 1.9.3。

---

## v2.1 AI 动态互动

- AI 好友按好感度概率点赞并评论用户动态，基础约 10%，满好感约 78%。
- 每条动态最多 2 个 AI 互动；视觉模型可读取动态图片。
- 动态页 8 秒静默轮询，AI 点赞/评论自动刷新。
- 服务端升级 1.9.3，新增 `moment_ai_reactions`。

---

## v2.0.2 服务端修复

- Web 导入 JSON 自动去除 BOM，并容忍数组/对象末尾多余逗号。
- 角色卡导入按钮再次解析时不会卡在 `Unexpected token ']`。
- 提供 `原神角色卡-fixed.json` 修复副本，可直接导入。
- 服务端版本 1.9.2。

---

## v2.0.1 服务端新增

- Web 控制台「角色卡」页新增「导入 JSON」，支持一个文件包含多个角色卡。
- 支持数组、单个对象、`{personas:[...]}`、`{characters:[...]}`、`{cards:[...]}`、`{data:[...]}`。
- 兼容 `name` / `char_name` / `character_name`、`description` / `background` / `profile`、`first_mes` / `greeting` 等常见角色卡字段。
- 同名公共角色卡自动跳过，错误条目单独计数；单次最多导入 500 张。
- 服务端版本 1.9.1，客户端继续使用 2.0.0。

---

## v2.0 新增：角色卡大世界

- 添加 AI 好友改为角色卡大世界：首页推荐、搜索、我的角色卡、管理员管理。
- 首页按使用数排序，2 列、每页 100 张，底部分页。
- 私人角色卡投稿后转为公共卡，管理员审核；拒绝会恢复为私人卡。
- 管理员可审核、编辑公共卡头像/名称/人设、删除公共卡。
- 服务端升级到 1.9.0。

---

## v1.9.8 修复

- 删除 AI 好友时清理本地会话/消息缓存，修复新 AI 复用会话 ID 后显示其他 AI 历史记录。
- 打开 AI 会话时校验缓存 `ai_friend_id` 和 sender name，不匹配直接丢弃。
- 服务端空消息时清理旧缓存。
- 仅客户端更新；服务端继续使用 1.8.1。

---

## v1.9.7 修复

- AI 好友卡片隐藏身份背景，只显示一句话人设和最后消息，限制 1 行省略。
- 私人角色卡副标题同样限制 1 行，长文本不会撑坏布局。
- 仅客户端更新；服务端继续使用 1.8.1。

---

## v1.9.6 新增

- 好友、消息、AI好友、动态全部本地缓存，切页先显示缓存，不再每次都闪“正在加载”。
- 网络请求返回后更新界面和缓存；无缓存时才显示加载提示。
- 设置 → 诊断新增「清除本地缓存」，可清好友/消息/AI好友/动态/头像缓存。
- 仅客户端更新；服务端继续使用 1.8.1。

---

## v1.9.5 修复

- 私人角色卡操作收进右侧 `⋮` 菜单：换头像、编辑、知识库、删除。
- 仅客户端更新；服务端继续使用 1.8.1。

---

## v1.9.4 新增

- 私人角色卡可在安卓端更换图片头像，并同步该角色卡创建的 AI 好友头像；普通角色卡仍只在 Web 管理。
- 设置里可添加自己的 OpenAI 兼容专属 Key，用于私人角色卡/私人知识库。
- 好友和群聊中长按自己发送的消息可复制或撤回；服务端标记撤回，双方显示「消息已撤回」。
- 服务端升级到 1.8.1，新增头像、私人 Key、撤回接口。

---

## v1.9.3 新增

- 聊天通知改用 HIGH 重要性新频道，默认声音、震动、锁屏可见、悬浮提醒；旧 DEFAULT 频道删除。
- 后台轮询 WakeLock、Service 重启与首次基线修正，减少后台收不到好友/AI 消息。
- 设置 → 诊断新增「通知与悬浮通知」入口。
- 仅客户端更新；服务端继续使用 1.8.0。

---

## v1.9.2 新增

- Dock 降高三分之一、外边距加宽三分之二，拖动气泡增加减速动画。
- 好友聊天设置：备注、只看对方动态、免打扰、置顶、单方面清空聊天记录、删除好友、拉黑。
- 我的页面新增「我的动态」，设置入口改为右上角齿轮。
- 服务端升级到 1.8.0，新增会话偏好/拉黑/单方面清空/用户动态接口。

---

## v1.9.1 新增

- 底部导航改为透明液态玻璃 Dock，三个入口：消息、AI好友、动态。
- 点击切换 + 长按拖动，松手切到最近入口。
- 好友与群聊合并到「消息」页；「日常」全面改名为「动态」。
- 仅客户端更新。

---

## v1.9 新增

- **文件管理开关**：设置里新增「显示文件管理导入」，默认关闭；关闭后所有图片入口只显示自建相册。
- **头像缓存**：内存 LruCache + 本地磁盘缓存，切页或重启优先读缓存。
- **日常 Markdown**：发布的文本直接兼容 Markdown，`# 标题`、粗体、列表、引用自动渲染；不再需要导入 `.md` 文件。
- 仅客户端更新；服务端继续使用 1.7.0。

---

## v1.8 新增

- **自建相册选择器**：MediaStore 查询手机内全部图片，3 列时间倒序网格，不再依赖 ColorOS 相册 Picker。
- **缩略图懒加载**：列表只解码 240px 缩略图并 LruCache 缓存，原图只在发送/上传时读取。
- 支持单选与多选（最多 9 张），聊天、日常、头像、背景、私人知识库截图统一使用。
- 无照片权限时显示授权入口，保留文件管理兜底。
- 仅客户端更新；服务端继续使用 1.7.0。

---

## v1.7.1 修复

- **ColorOS 相册 URI 修复**：日志确认 `picker_get_content` URI 全部 `ENOENT`；OnePlus/OPPO/realme 改走 DocumentsUI `DCIM/Camera`，避开相册 Picker。
- 增加 picker URI 还原 MediaStore URI 的兜底，并在日志记录权限状态和最终 URI。
- 仅客户端更新；服务端继续使用 1.7.0。

---

## v1.7 新增

- **相册修复**：ColorOS 相册 Picker 返回的虚拟 URI 在 1.7.1 改为 DocumentsUI 绕过；1.7.0 的旧版 Picker 方案作为历史记录保留。
- **调试日志**：设置页可导出 txt，包含设备、版本、媒体选择 URI、打开失败原因、API 错误。
- **我的页面**：主界面右上角用户名 + 圆形头像进入「我的」；展示头像、昵称、性别、签名、标签。
- **设置页面**：个人资料、头像上传、AI 审核、主题、聊天背景、权限状态、调试日志、退出登录。
- **本地缓存**：SQLite 保存好友、会话和每个会话最近 300 条消息；断网时先展示本地记录。
- 服务端资料新增 `gender` / `tags`，并提供 `/api/v1/auth/profile/review` AI 审核接口。

---

## v1.6 新增

- **退出登录保留常用表情包**：清理登录态时保留 `sticker_favorites`，重新登录后仍在。
- **照片/视频权限**：Android 13+ 请求 `READ_MEDIA_IMAGES/VIDEO`，Android 14+ 兼容 `READ_MEDIA_VISUAL_USER_SELECTED`，旧系统请求存储权限。
- **私人知识库**：私人角色卡新增「知识库」管理；可新建/删除、导入 TXT、导入聊天记录长截图并通过视觉模型 OCR、按角色绑定/解绑。
- 服务端新增 `/api/v1/ai/knowledge` 私人知识库接口和 `/api/v1/ai/personas/{id}/knowledge` 绑定接口；知识库按 owner_id 隔离。

---

## v1.5 新增

- **表情包搜索/发送**：接入 `api.ruseo.cn/api/bqb`，关键词搜索或随机；聊天、群聊、AI 会话均可发送。
- **words 语义**：表情消息携带 `sticker_url` + `words`；AI 跳过视觉识图，直接按 words 理解；会话预览显示 `[words]`。
- **常用表情包**：长按聊天里别人或自己的表情包收藏，选择面板顶部优先展示；搜索结果的 GIF 在 Android 9+ 播放动画。
- 服务端 `MessageIn` 增加 `sticker_url` / `words`，图片识别分支与表情包分支互斥。

---

## v1.4 新增

- **图片下载**：聊天、群聊、日常图片全屏页新增「下载」，保存到系统相册 `Pictures/NekoMate`。
- **Markdown 日常**：编辑器支持导入 `.md` 文件，客户端渲染标题、列表、粗体、斜体、链接、引用和代码块。
- **视频嵌入**：日常中出现 B 站视频/抖音视频标准链接时，自动移除裸链接并显示视频卡片，应用内嵌播放器默认暂停。
- **平台边界**：只识别 `bilibili.com/video/...`、`douyin.com/video/...`、`iesdouyin.com/share/video/...`；B 站动态/专栏、抖音图文不会被当成视频。
- 服务端日常内容上限提高到 30000 字符，用于 Markdown 文档。

---

## v1.3 新增

- **日常图片查看/删除**：动态图片点击全屏查看；自己的日常可删除。
- **聊天发图片**：输入栏图片按钮，相册/文件管理两个入口；服务端上传后，AI 好友通过视觉模型识别图片内容再回复。
- **好感度系统**：每个 AI 好友默认 0，可在 AI 设置开关/重置；分析模型每次聊天返回 `affinity_delta`，普通对话为 0，AI 语气随好感度分档变化。
- **免打扰时间段**：AI 设置里填写开始/结束 `HH:mm`，主动消息在该时间段跳过。
- **视觉模型**：管理面板新增 `vision` 模型类型，用于聊天图片识别；没有视觉模型时图片仍可发送，AI 会说明识别暂不可用。
- 服务端升级到 **1.3.0**，SQLite 自动建表/加列，不需要删数据。

---

## v1.2.2 修复

- 日常发布、聊天背景新增「从文件管理选择」，使用 `ACTION_OPEN_DOCUMENT` + `EXTRA_LOCAL_ONLY`，绕开 OPPO 文件管理返回的失效 URI。
- 相册仍走系统 Photo Picker；选中后先读取并复制到 App 私有目录，再尝试持久化授权。
- `content://` 依次尝试 `openInputStream`、`openFileDescriptor`、`openAssetFileDescriptor`，`file://` 用 `FileInputStream` 兜底。
- 服务端不变，继续使用 1.2.0 镜像。

---

## v1.2 新增

- **后台消息通知补齐**：好友私聊、群聊、AI 好友的新消息都会通过通知栏推送，切到后台也能收到；通知标题显示好友/群名，群消息正文带发送者。
- **群成员管理**：群创建后还能继续邀请好友；群设置里可以邀请自己名下的 AI 好友进群，也可以移除 AI。
- **群内 AI 接话**：被拉进群的 AI 会结合群成员、最近聊天记录和最后一条消息自动回复；AI 所属用户有专属 Key 时走专属 Key。
- **全屏日常编辑器**：替换原来的弹窗，支持 2000 字、最多 9 张图片、缩略图预览/长按移除、图片上传，动态列表直接显示图片。
- 服务端新增 `conversation_ai_members` 表；旧库自动建表，不需要删数据。

---

## v1.1 新增

- **管理员给每个用户单独分配模型 Key**：Web 控制台 →「用户」→「专属Key」，或「模型提供商」里把 Key 的归属设为某个用户。该用户的聊天、主动消息、记忆分析只走这把 Key，不消耗全局 Key；其他用户也看不到、用不到这把 Key。
- **私人角色卡**：只有被分配了专属 Key 的用户，才能在安卓端「AI 好友」里创建/编辑/删除自己的私人角色卡；不同专属 Key 用户之间不互通。
- **浅色 / 深色主题**：安卓端默认浅色，可在「我的」里切换，全部页面生效。
- **自定义聊天背景**：安卓端从系统图片选择器选 PNG/图片，聊天页自动加遮罩。
- **客户端云端更新**：Web 控制台新增「客户端更新」，上传 APK、versionCode、versionName、更新日志、是否强制；客户端启动检查并弹窗，从服务端下载安装包。
- 服务端 SQLite 自动迁移旧库，不需要删数据。

---

## 0. 本次交付

| 文件 | 说明 |
| --- | --- |
| `nekomate-server-1.7.0.tar` | Docker 本地镜像（约 70MB，`docker load` 直接可用） |
| `nekomate-server-1.7.0-source.zip` | 服务端完整源码（改代码 / 自己构建时用） |
| `SHA256SUMS.txt` | 校验值，上传后可用 `sha256sum -c` 校验 |
| `README.md` | 本文档 |
| `docker-compose.yml` | 可选，服务器上用 compose 启动 |
| `.env.example` | 环境变量示例 |

镜像内已包含：完整服务端代码、管理面板静态资源、AstrBot 兼容层、两个内置示例插件。

更新到 1.3.0 只需要重新 `docker load` 新镜像并重启容器，`/data/nekomate.db` 会自动加列迁移，原账号、聊天、角色卡、知识库都保留。安卓客户端 APK 与服务端镜像的版本号已对齐为 1.3.0。

---

## 1. 镜像部署

### 1.1 上传并加载

在本地拿到 `nekomate-server-1.7.0.tar` 后：

```bash
# 上传
scp nekomate-server-1.7.0.tar root@YOUR_SERVER:/root/

# 校验（可选）
sha256sum nekomate-server-1.7.0.tar

# 服务器上加载
docker load -i /root/nekomate-server-1.7.0.tar
docker images | grep nekomate
```

### 1.2 启动容器

```bash
mkdir -p /opt/nekomate/data

docker run -d \
  --name nekomate \
  --restart unless-stopped \
  -p 8080:8080 \
  -v /opt/nekomate/data:/data \
  -e NM_PUBLIC_BASE_URL=http://YOUR_SERVER:8080 \
  -e NM_ADMIN_USERNAME=admin \
  -e NM_ADMIN_PASSWORD=change-me-now \
  -e TZ=Asia/Shanghai \
  nekomate-server:1.9.18
```

或者把 `docker-compose.yml` 一起上传到服务器，在文件所在目录执行：

```bash
docker compose up -d
```

端口可改：把 `-p 8080:8080` 换成服务器的实际端口，同时改 `NM_PUBLIC_BASE_URL`。
如果前面有 Nginx / Caddy 反代，建议加上 HTTPS，并把 `NM_PUBLIC_BASE_URL` 改成外网地址。

### 1.3 检查

```bash
docker logs -f nekomate
curl http://127.0.0.1:8080/api/v1/health
```

浏览器打开 `http://YOUR_SERVER:8080/admin`：

- 默认账号：`admin`
- 默认密码：`change-me-now`（环境变量可改；登录后请立即在「设置」里修改）

---

## 2. 五分钟开箱流程

1. **模型提供商** → 快速添加 DeepSeek / 智谱 / 通义 / Moonshot / OpenAI，填 API Key，点「测试」。
   - 对话模型：聊天、主动消息、应用事件回复
   - 分析模型：判断一句话是否值得长期记忆（不配置时自动复用对话模型）
   - 图像模型：AI 生成图片
2. **知识库** → 新建 → 导入微信 / QQ 聊天记录 → 自动识别说话人、生成语气画像与对话样本。
3. **角色卡** → 新建：身份、性格、说话方式、口头禅、打招呼语，并勾选关联的知识库。
4. **用户**：客户端注册后即可在「用户」里看到；注册时会自动赠送一个默认 AI 好友（可在设置里关闭）。
5. **未来任务** → 新建「每天 08:30 说早安」之类，服务端到点执行。

不配置模型也能跑：所有 AI 回复会带「（演示模式）」前缀，方便先验证链路。

---

## 3. 功能说明

### 3.1 账号与社交（客户端 API）

| 模块 | 说明 |
| --- | --- |
| 注册 / 登录 | JWT，密码 bcrypt 存储；账号数据在服务端 SQLite |
| 好友 | 搜索、申请、同意 / 拒绝、备注、删除；聊天记录云端保存 |
| 群聊 | 建群、邀请、退群 / 移出，系统提示消息 |
| 日常空间 | 发动态、图片、点赞、评论，好友可见 |
| 实时 | WebSocket `/api/v1/ws?token=...`，新消息 / 好友申请 / 动态通知即时推送 |

### 3.2 AI 好友

- 每个用户可以添加多张角色卡作为 AI 好友。
- 聊天走「角色卡 + 语气样本 + 记忆 + 最近对话」拼装后的提示词。
- **记忆**：每条用户消息交给分析模型判断重要性；重要 → 写入长期记忆（不设过期），不重要 → 短期记忆只保留 2 天（可配置）。过期短期记忆由调度器自动清理，长期记忆按重要度与时间衰减检索。
- **主动消息**：
  - 用户超过 30 分钟（`idle_minutes`）不说话 → 本次会话结束；
  - 随机 1–3 小时（`min_delay_minutes` ~ `max_delay_minutes`）后 AI 主动发一条消息；
  - 内容来源随机：之前的聊天、用户城市的天气（wttr.in，无需 Key）、日常闲聊；
  - 用户一直不回，最多连续主动 3 次（`max_streak`）后停止，等用户回复后再重新循环；
  - 支持免打扰时段（`quiet_start` / `quiet_end`）。
- **应用白名单（root）**：
  - 客户端开启 root 权限后，用户打开白名单应用时上报事件：
    `POST /api/v1/ai/friends/{id}/usage-events`
  - 服务端匹配包名 / 备注，结合时间与天气生成一句自然的话，并写入会话、实时推送；
  - 默认冷却 15 分钟，避免连续轰炸；不在白名单的应用只记录不回复；
  - 内置常见应用白名单（外卖、淘宝、微信、抖音、B 站、王者荣耀等 18 个），可在客户端或 API 增删。

### 3.3 知识库（语气学习）

支持格式：

- QQ 导出 txt：`2024-05-01 12:00:00 昵称(123456)` 换行接消息
- 微信导出 txt：`昵称 2024-05-01 12:00:00`、`2024-05-01 12:00:00 昵称`、`[时间] 昵称: 内容`
- JSON：`[{"sender": "...", "content": "...", "time": "..."}]` 或 `{"messages": [...]}`
- CSV：表头含 `sender/content/time` 或「昵称/内容/时间」
- HTML 导出：自动去标签后按文本解析
- 直接粘贴文本

导入后会自动：

- 识别说话人（可手动指定），统计消息量
- 生成风格画像：平均句长、emoji 频率、不带标点比例、高频词、常见开头/结尾、真实语句
- 生成「上下文 + 目标回复」对话样本，供提示词模仿语气（不照抄内容）

把知识库关联到角色卡后，AI 会模仿该说话人的节奏与用词。

### 3.4 插件（兼容 AstrBot）

- 插件目录：`/data/plugins/<slug>/`，管理面板可上传 zip、启停、改配置、看源码、删除、热重载。
- 兼容 AstrBot 常用写法：

```python
from astrbot.api.event import filter, AstrMessageEvent
from astrbot.api.star import Context, Star, register

@register("hello", "author", "desc", "1.0.0")
class Hello(Star):
    def __init__(self, context: Context):
        super().__init__(context)

    @filter.command("hello", alias={"hi"})
    async def hello(self, event: AstrMessageEvent):
        yield event.plain_result(f"你好，{event.get_sender_name()}")
```

- 支持：`@register`、`Star` / `Context`、`@filter.command` / `regex` / `event_message_type`、
  `plain_result` / `image_result`、`message_components`、`metadata.yaml`、`_conf_schema.json`、
  相对导入、插件调用宿主模型（`self.context.get_using_provider().text_chat()`）。
- 插件命中后，本轮由插件回复；未命中则走 AI 对话。聊天里直接发 `/hello` 即可测试。
- 内置示例：`hello_world`（打招呼）、`neko_tools`（骰子 / 运势 / 时间）。

### 3.5 未来任务

- 类型：定时消息（固定文案或模型生成）、强制主动搭话、生成日常动态、执行插件命令
- 计划：一次性、固定间隔、每天定时、Cron（`*/30 9-22 * * *`）、仅手动
- 支持立即执行、执行历史、执行结果与错误日志

---

## 4. 目录与数据

```
/data
├── nekomate.db          # 全部业务数据
├── secret.key           # JWT 签名密钥（自动生成）
├── uploads/             # 上传的图片 / 语音
├── knowledge/           # 导入的聊天记录（原始文本也入库）
└── plugins/             # AstrBot 兼容插件
```

备份：直接打包 `/opt/nekomate/data` 即可（建议先 `docker stop nekomate` 再复制）。

升级：`docker load` 新镜像 → `docker rm -f nekomate` → 用同样的 `-v` 重新 `docker run`，数据不丢。

---

## 5. 主要 API（客户端接入用）

统一前缀 `/api/v1`，除注册登录外都需要 `Authorization: Bearer <token>`。

```
POST /auth/register            {username,password,nickname}
POST /auth/login               {username,password} → {token,user}
GET  /auth/me
PUT  /auth/profile             {nickname,avatar,bio,city}
PUT  /auth/password            {old_password,new_password}

GET  /friends                  GET /friends/search?q=
POST /friends/requests         {username|user_id,message}
GET  /friends/requests
POST /friends/requests/{id}/accept | /reject
PUT  /friends/{id}             {remark}

GET  /conversations
POST /conversations/direct     {user_id}
POST /conversations/group      {title,member_ids}
GET  /conversations/{id}/messages?before_id=&limit=
POST /conversations/{id}/messages   {content} → {message,reply}
POST /conversations/{id}/read

GET  /ai/personas
GET  /ai/friends
POST /ai/friends               {persona_id,name?}
PUT  /ai/friends/{id}          {root_enabled,settings,...}
POST /ai/friends/{id}/chat     {content} → {message,reply}
POST /ai/friends/{id}/usage-events {events:[{package,label,ts}]}
GET  /ai/friends/{id}/whitelist   POST /ai/friends/{id}/whitelist
GET  /ai/friends/{id}/memories
GET  /ai/friends/{id}/proactive
POST /ai/friends/{id}/image    {prompt,size}
POST /ai/friends/{id}/reset

GET  /moments?scope=friends|mine
POST /moments                  {content,images}
POST /moments/{id}/like
POST /moments/{id}/comments    {content}
POST /upload                   multipart file
WS   /api/v1/ws?token=
```

完整参数见 `/docs`（Swagger）。

---

## 6. 环境变量

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `NM_DATA_DIR` | `/data` | 数据目录 |
| `NM_PORT` | `8080` | 监听端口（也支持 `PORT`） |
| `NM_PUBLIC_BASE_URL` | `http://YOUR_SERVER:8080` | 对外地址，用于上传文件 URL |
| `NM_ADMIN_USERNAME` | `admin` | 管理员账号 |
| `NM_ADMIN_PASSWORD` | `change-me-now` | 管理员密码（首次启动创建） |
| `NM_REGISTRATION_ENABLED` | `true` | 是否开放注册（与面板开关同时生效） |
| `NM_AUTO_CREATE_AI_FRIEND` | `true` | 注册时自动创建 AI 好友 |
| `NM_TOKEN_TTL_HOURS` | `720` | 登录有效期 |
| `NM_CORS_ORIGINS` | `*` | 跨域来源 |
| `NM_DEFAULT_IDLE_MINUTES` | `30` | 空闲结束会话阈值 |
| `NM_DEFAULT_PROACTIVE_MIN_MINUTES` | `60` | 主动消息最小间隔 |
| `NM_DEFAULT_PROACTIVE_MAX_MINUTES` | `180` | 主动消息最大间隔 |
| `NM_DEFAULT_PROACTIVE_MAX_STREAK` | `3` | 连续主动上限 |
| `NM_DEFAULT_SHORT_MEMORY_DAYS` | `2` | 短期记忆保留天数 |

---

## 7. 说明与限制

- 单容器单 worker（内置调度器、WebSocket 与内存推送都在进程内），不要加 `--workers > 1`。
- 默认 SQLite，适合个人 / 小团队；数据结构与 SQLAlchemy 兼容 PostgreSQL，可自行扩展。
- 记忆检索使用「重要度 + 时间衰减 + 关键词重叠」，不依赖 embedding 服务；后续可加向量检索。
- 插件为 AstrBot 兼容子集：命令、正则、事件类型、消息组件、配置、宿主模型调用可用；
  依赖 AstrBot 平台适配器（QQ / 微信协议端）的插件无法直接工作。
- 天气来自 `wttr.in`，无 Key，网络受限时会自动跳过天气来源。
- 上线前请：修改管理员密码、设置强随机 `NM_SECRET_KEY`、使用 HTTPS 反向代理、限制 8080 公网暴露。

---

## 8. 已验证项（构建时自测）

- API 端到端 43 项：注册登录、好友申请 / 同意、私聊、群聊、AI 好友、聊天、记忆、主动消息、应用事件、白名单、动态、任务、用户删除级联、日志、系统状态
- 真实模型链路 11 项：OpenAI 兼容对话、分析模型 JSON、图像生成、provider 连通性测试（本地 mock 服务）
- 主动消息逻辑：30 分钟空闲 → 1–3 小时随机 → 连续 3 次 → 停止 → 用户回复后恢复
- 记忆逻辑：重要内容长期保存、普通内容 2 天过期
- AstrBot 插件：内置命令、正则、zip 热上传、相对导入、配置读写、启停
- WebSocket：登录鉴权、AI 回复实时推送、ping/pong
- 管理面板：9 个页面无 JS 报错，登录 / 欢迎 / 知识库导入 / 设置保存可用
- 容器：`docker load`、健康检查、数据卷持久化、文件上传与 `/media` 访问

