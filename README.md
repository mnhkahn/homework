# 作业小伙伴 Android 平板应用

这是一个面向小学生的单页作业执行应用。横屏平板显示“当前任务 + 待完成任务”双栏，窄屏时自动变为单列。

## 已实现

- 首次启动询问并本地保存孩子的名字
- 当日任务进度、当前任务和待完成任务
- 原地开始/暂停倒计时；倒计时结束后标记为超时
- 系统相机拍摄作业，确认后将任务标记为完成
- 适配横屏平板与窄屏设备的 Compose 布局

## 运行

用 Android Studio 打开该目录，并选择 Android API 35 的模拟器或实体平板运行即可。

## 学习模式网站管控

Device Owner 模式下，Chrome 始终加入学习应用白名单。进入学习模式时向 Chrome 下发 `URLBlocklist = ["*"]` 和 `URLAllowlist = ["cyeam.com", "trello.com", "pgyer.com"]`（学习页、Trello 授权页、蒲公英更新下载页）；Chrome 的域名规则包含主域名及全部子域名（无需使用 `*.cyeam.com`）。页面静态资源不逐个加入导航白名单，跨站导航仍受限制。

`URLBlocklist`/`URLAllowlist` 是 Chrome 专有的托管配置，其他浏览器不识别。因此学习模式会通过可浏览 HTTPS 的应用查询识别所有已安装浏览器，把 Chrome 以外的浏览器一律挂起（即使曾被勾选），家长设置中这些浏览器的勾选框也被禁用。

进入学习模式前持久保存原有的两项网址策略，退出、定时结束或临时开放时恢复；其他 Chrome 托管配置保留。应用内 WebView 继续使用现有的 HTTPS Cyeam 域名检查。

设备验收：在 `chrome://policy` 确认两项策略状态和值，测试主域名、子域名、站外跳转及登录/音频流程；再测试临时开放、恢复学习、定时结束和学习期间重启。Chrome 策略实际生效需要在目标设备验证。

## Trello 同步

任务模型和 `HomeworkTaskSource` 已作为独立接口放在 `app/src/main/java/com/homeworkbuddy/HomeworkTask.kt`。当前页面使用 `PreviewTaskSource` 的演示任务，以便在尚未配置服务端时完整验证界面和交互。

正式接入时应在自有服务端实现：

1. 服务端以 Trello API token 读取当天的卡片并转换为 `HomeworkTask`。
2. 应用通过 HTTPS 拉取当天任务，并将完成时间和照片上传给服务端。
3. 服务端再把提交状态、照片链接或附件写回 Trello。

不要把 Trello API token 打包进 APK。

## 发布与自动升级

推送 `v*` 或 `x.y.z` 形式的 tag 会触发 `.github/workflows/android-release.yml`：CI 用 GitHub Secrets 里的 keystore 签名 release APK（版本号取自 tag，`versionCode` 取 workflow run number），同时发布到蒲公英和 GitHub Release。发布时会用 `git-chglog` 按版本号生成变更说明，并同时写入 GitHub Release 与蒲公英更新说明。App 会直接读取蒲公英公开下载页的版本与更新说明；发现更新后打开该页，由蒲公英生成短时下载链接并完成安装，因此 APK 内不保存蒲公英 API Key。

首次配置需要：

1. 生成发布 keystore：`keytool -genkeypair -v -keystore release.keystore -alias homeworkbuddy -keyalg RSA -keysize 2048 -validity 10000`（keystore 只保留在本地，不要提交）。
2. 在仓库 Settings → Secrets and variables → Actions 添加 `RELEASE_KEYSTORE_BASE64`（`base64 -i release.keystore` 的输出）、`HOMEWORK_RELEASE_STORE_PASSWORD`，以及蒲公英后台获取的 `PGYER_API_KEY`。
3. 每个发布构建都会等待蒲公英完成发布；成功后 Actions 日志和飞书通知会显示固定的蒲公英下载页链接。安装方式为公开安装、无有效期。
4. 平板上首次从 debug 签名切换到 release 签名时需卸载重装一次；之后同签名版本即可自动覆盖升级。

## 小李 Gateway 连接

家长在小李管理端点击“添加学习平板”生成一次性二维码，再在“家长设置 → 小李连接”中扫描。设备 token 使用 Android Keystore 的 AES-GCM 密钥加密后才会保存到本机。二维码内容是：

```json
{"pair_url":"https://xiaoli-server.example/xiaozhi/pair","code":"short-lived-one-time-code"}
```

应用向 `pair_url` 提交设备 ID、名称和 `device_kind=android`。在 Logto 会话所属的用户确认后，Gateway 应返回：

```json
{
  "device": {"id":"homework-tablet-abcd1234", "name":"小明的学习平板"},
  "websocket": {"url":"wss://xiaoli-server.example/xiaozhi/v1/", "token":"device-token"}
}
```

连接成功后，应用声明并实现以下 MCP 工具：

- `self.device.get_status`
- `self.homework.get_status`
- `self.homework.get_weekly_report`（查询本周作业完成周报）
- `self.notify.send`
- `self.audio_speaker.play_ogg_url`（流式播放服务端提供的 Ogg/Opus 音频）
- `self.audio_speaker.stop`（停止当前音频播放）
- `self.camera.take_photo`（应用内单次拍照；会显示“正在拍照”提示并播放提示音，不打开系统相机）
- `self.camera.record_video`（前台调起系统相机录制小视频，默认最长 15 秒、上限 30 秒）
- `self.camera.start_stream` / `self.camera.stop_stream`（应用内共享学习画面；平板提示并播放提示音，1–3 fps、最长 60 秒）
- `self.kiosk.pause_15_minutes`（复用既有的临时开放 15 分钟逻辑）

这里的 MCP 是手写 JSON-RPC over WebSocket（小智风格 envelope），未使用官方 MCP SDK——因为服务端不是标准 MCP transport，官方 SDK 无内置 WebSocket transport。

## 本地头部状态

查看今天的页面时，日期栏下方始终显示头部状态，包括没有作业、未选中作业和全部完成的情况。作业页在前台且获得相机权限后，以约 3 次/秒运行 APK 内置的 ML Kit 人脸模型；画面仅在内存中处理，不保存、不上传。离开作业页、进入后台或拍照/共享画面时让出相机，返回后自动恢复。

- 俯仰角和左右转角：相对摄像头的角度，正俯仰角表示抬头，负值表示低头。
- 相对距离：最初 5 帧处于设定角度范围内的稳定观测自动建立并保存 1.00× 基准，大于 1 表示更远。这是同一人相对平板的粗略距离比例，不是厘米，也不是头到书本的距离。
- 多人、无人、眼部遮挡或俯仰/转头角超过 35° 时，不显示距离估计；人脸丢失或检测重启后沿用已保存的基准；不同相机分辨率和方向分别保存。移动平板或换人后，在家长设置中重新校准。

实机验收：首次权限允许/拒绝、抬头低头、左右转头、前后移动、无人/多人、横竖屏切换、后台返回，以及检测期间远程拍照/共享后恢复。确认模型在断网状态下仍有输出。

### 头部姿势提醒

家长设置 → 头部姿势提醒可保存三个指标的上下限、触发/恢复秒数和提示音选项，也可恢复默认范围或重新校准距离。默认俯仰 −29°～−9°、左右转角 −23°～7°、距离 0.85×～1.20×；这些是本次实测姿势的试用范围，不是医学标准。

同一项连续超限 3 秒后，指标栏变色并显示调整提示；正常持续 2 秒后解除。超限开始时响一声媒体通道短提示音，声音至少间隔 30 秒。无人、多人、画面过期或相机暂停时清除提醒计时；无法估算距离时仅暂停距离提醒。
