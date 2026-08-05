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

## Trello 同步

任务模型和 `HomeworkTaskSource` 已作为独立接口放在 `app/src/main/java/com/homeworkbuddy/HomeworkTask.kt`。当前页面使用 `PreviewTaskSource` 的演示任务，以便在尚未配置服务端时完整验证界面和交互。

正式接入时应在自有服务端实现：

1. 服务端以 Trello API token 读取当天的卡片并转换为 `HomeworkTask`。
2. 应用通过 HTTPS 拉取当天任务，并将完成时间和照片上传给服务端。
3. 服务端再把提交状态、照片链接或附件写回 Trello。

不要把 Trello API token 打包进 APK。

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
- `self.camera.take_photo`（会显示系统相机，绝不静默拍摄）
- `self.camera.record_video`（前台调起系统相机录制小视频，默认最长 15 秒、上限 30 秒）
- `self.device.screenshot`（前台弹系统授权框后截取当前屏幕画面）
- `self.kiosk.pause_15_minutes`（复用既有的临时开放 15 分钟逻辑）

这里的 MCP 是手写 JSON-RPC over WebSocket（小智风格 envelope），未使用官方 MCP SDK——因为服务端不是标准 MCP transport，官方 SDK 无内置 WebSocket transport。
