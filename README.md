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
