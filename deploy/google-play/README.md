# 作业小伙伴 Google Play 封测

Play 构建使用 `com.homeworkbuddy`。首个候选版本是 1.0.0，versionCode 为 10001，最低 API 26，目标 API 36。蒲公英和受管平板的构建入口为 `release`。

## 构建

使用 JDK 17、Gradle 8.11.1 及 Android SDK Platform 36。首次创建 Play 应用时先核对包名和签名安排。若希望现有蒲公英安装能由 Play 原地更新，需要在开通 Play App Signing 时使用匹配的原签名密钥。Play 上传密钥只负责签 AAB，与 Play 为用户安装包使用的签名密钥分开。若选择由 Google 生成另一把签名密钥，现有安装不能直接覆盖升级。

```sh
gradle :app:testPlayReleaseUnitTest :app:bundlePlayRelease \
  -PreleaseVersionName=1.0.0 -PreleaseVersionCode=10001
```

从环境变量 `HOMEWORK_PLAY_STORE_FILE` 和 `HOMEWORK_PLAY_STORE_PASSWORD` 读取 Play 上传签名配置。签名别名为 `homework-play-upload`。请备份密钥并保管密码，后续版本使用同一个已注册的上传证书并递增 versionCode。

AAB 生成在 `app/build/outputs/bundle/playRelease/`。每次上传前确认 AAB 签名、包名、目标 API、原生库 16 KB 对齐，并在 API 36 设备验证启动、计时、提交以及权限交互。

## 审核资料

- [商店信息](store-listing.md)包括名称、短文案、完整说明和客服邮箱。[商店素材](assets/)包含图标、宣传图和Android 16 手机模拟器截图。
- [应用内容](app-content.md)包括可直接使用的英文审核步骤，以及数据安全和受众填报依据。
- [隐私政策源文件](privacy/index.html)部署在 [公开隐私政策](https://homework-buddy-privacy.vercel.app/)。
- 真机封测请使用专用 Trello 私有看板及虚构作业。审核员也可以直接使用免登录示例，示例数据留在设备上。
- 在 Play Console 选免费和封闭式测试，完成应用内容的各项申报、签名和测试者名单后送审。现时新个人开发者账号需要达到至少 12 名测试者连续加入该应用封测 14 天，才可申请正式发布权限。

[Google Play 封测要求](https://support.google.com/googleplay/android-developer/answer/14151465) · [原生库 16 KB 校验](https://developer.android.com/guide/practices/page-sizes) · [Play App Signing](https://support.google.com/googleplay/android-developer/answer/9842756)
