# Play Console 应用内容草稿

## 应用访问

The app UI is in Simplified Chinese. On first launch, tap “体验示例” (Try demo) on the nickname prompt. No login, token, or credentials are required for sample assignments.

1. Open “口算练习” (Arithmetic practice) or “写一句观察日记” (Write an observation).
2. Tap “开始做” (Start) and then “完成并提交” (Finish and submit).
3. Choose “提交文字” (Submit text), enter a sample response, and confirm. The assignment moves to the completed section. The other assignment can be used to try the camera or audio flow. Demo submissions stay on the device.
4. Tap “退出示例” (Exit demo) to return to regular setup.

To exercise live sync, a parent authorizes the app in a browser with an adult Trello account, pastes Trello’s authorization code into the app, then selects a private board. Use a dedicated test board containing fictional assignments for live-sync testing. Keep credentials and authorization tokens out of this file.

## 目标受众和内容分级

- 作业执行界面面向小学阶段儿童，按实际投放范围如实申报适用年龄组。家长负责第三方账号和同步配置。根据 Play Console 问卷完整申报儿童和家长受众。
- 没有广告、内购、订阅或公开社交。作业可以提交到家长授权的私人第三方看板，其中的媒体对看板成员可见。问卷询问用户生成内容、内容分享或外部网页时，按功能如实回答。
- 依据线上 Trello 流程与目标市场确认适用的儿童数据处理要求后再定稿。示例模式不要求儿童使用 Trello 账号。

## 数据安全填报依据

| 运行路径 | 涉及数据和用途 |
| --- | --- |
| 家长关联私有 Trello 看板 | 凭据保留在设备上并在对 Trello 的 HTTPS 请求中用于鉴权。应用读取获准访问的看板、卡片、评论和附件。 |
| 作业及计时记录同步 | 家庭启用同步后，向 Trello 发送任务开始和完成记录及实际用时。以应用功能为目的。 |
| 确认提交作业 | 用户选择提交的文字、照片和录音，以及适用的预习要求发送到 Trello。通过 HTTPS 传输。 |
| 学习网页、词汇及朗读 | 用户打开的网页及在线发音服务会收到相应资源请求或教材词句和连接信息。发音途径涉及 Cyeam 和有道。 |
| 昵称、家长设置、示例 | 昵称、家长 PIN、动态验证码密钥和示例进度留在设备上。示例成果不发送。 |
| 删除 | 用户可清除应用数据。家长可在 Trello 管理和删除云端内容、撤销授权。隐私政策列出 Cyeam 联系邮箱。 |

按发布包中完整的数据流逐项判断数据安全问卷的「收集」「共享」及用户主动传输例外。第三方处理和数据安全标签适用例外时，隐私政策仍说明实际传输。应用不创建独立账号。已移除广告标识符权限以及 Firebase Analytics、Crashlytics。
