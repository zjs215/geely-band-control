# 第三方依赖与素材

项目原创源码及 `scripts/generate-assets.ps1` 生成的展示素材采用 Apache-2.0。吉利银河、小米、米家及相关产品名称属于各自权利人的标识，用于描述兼容关系。腕上车控由独立开发者维护。

| 内容 | 来源与处理 |
| --- | --- |
| Gradle Wrapper | Gradle，Apache-2.0。保留启动脚本原有声明，包含 wrapper JAR 和配置 |
| Android Gradle Plugin | Google Maven 下载，Apache-2.0，固定版本见 `android/build.gradle` |
| JUnit 4 | 测试依赖，EPL-1.0，版本见 `android/app/build.gradle` |
| JSON-java | 测试依赖，版本 20240303，使用该版本上游许可证 |
| minstall | 安装助手的手环协议实现参考 0.2.0 版，保留 MIT 许可与版权声明 |
| libadb-android-bc、Bouncy Castle | 安装助手的无线 ADB 和加密依赖，分别固定 3.5.1 和 1.84。libadb-android-bc 采用上游提供的 Apache-2.0 授权选项，保留组件各自的许可证 |
| AIoT Toolkit | npm 的 `aiot-toolkit` 2.0.5 及传递依赖，锁文件保留版本、完整性和许可证信息。使用时遵循各包许可证 |
| 小米 Wearable SDK | 官方通信样例内 `xms-wearable-lib_1.4_release.aar`。源码仓库通过构建脚本从官方源取得并校验，不附带该二进制。当前尚未确认独立再分发授权，公开包含该 SDK 的 APK 前需核对小米许可 |
| 银河官方卡片 | 通过 Android AppWidgetHost 托管用户已安装 App 提供的 RemoteViews。官方 App、图标、车图和反编译实现由各自权利人持有 |
| 吉利汽车备用图标 | 来自[小米应用商店的吉利汽车条目](https://r.app.xiaomi.com/details?id=com.geely.consumer)，仅用于标识可连接的官方 App，图标权利归原权利人 |
| 米家手动控制截图 | 米家 App 的设置界面截图，供指令码引导示例使用，界面及标识权利归原权利人 |
| 主题背景 | 来自「小领通」APK 与 `xiaolingtong-v0.3.31` 源码的原始场景，用于三端主题试装。Android 保留原 WebP，Vela 使用同一素材的 JPG 屏形裁切版 |

小米 SDK 官方样例地址和 SHA-256 见 `scripts/prepare.ps1`。依赖在构建过程中获取，下载和使用应遵循各自条款。

2026 年 11 月 25 日的官方文档、样例与 AAR 核查仍未确认 GitHub APK 再分发许可，详见 [核查记录](docs/xiaomi-sdk-license-review.md)。

项目展示车图为原创通用轿车示意，应用图标为原创腕表与轨道图形，线性操作图标为项目自绘。既有本地官方衍生素材备份留在 `.local`，公开源码导出时排除。

参考资料见下列上游入口。

- https://iot.mi.com/vela/quickapp/zh/features/network/interconnect.html
- https://github.com/gradle/gradle
- https://github.com/junit-team/junit4
- https://github.com/stleary/JSON-java
