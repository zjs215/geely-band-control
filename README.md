# 口袋车控（穿戴端：腕上车控）

Android 手机应用「**口袋车控**」可读取**吉利银河、吉利汽车、中国版领克和极氪**官方小组件的车况，并支持配置**米家语音直控**。搭配穿戴端「**腕上车控**」，可在小米手环 10 Pro / 9 Pro / 8 Pro、小米手环 10、小米 Watch 5 或支持 Vela RPK 的圆形手表上查看车况并执行控车操作；配合「**车控安装助手**」可在手机端一键安装或更新各端应用。

口袋车控 / 腕上车控由独立开发者维护，为非官方开源辅助工具，与吉利汽车、领克、极氪、小米及米家无官方或授权关联。车主在手机端授权托管对应品牌官方小组件，通过本地识别与操作转交完成控车；实际车况与车控能力由官方 App、车型和车辆状态提供。

> [!WARNING]
> **使用安全与免责提示**
> - 本项目按 [Apache-2.0](LICENSE)「现状」提供，不包含任何明示或默示担保；因网络/蓝牙延迟、官方卡片改版、误触或状态同步滞后造成的车辆状态差异，请以对应品牌官方 App 显示为准。
> - 开启「允许车控」后，穿戴或米家语音操作按动作的二次确认设置触发已绑定小组件的官方按钮。请务必为手环或手表开启**锁屏密码**，并在非本人佩戴、设备外借或长期不用时及时关闭车控授权。

[更新日志](CHANGELOG.md) · [兼容性与验证范围](docs/compatibility.md) · [车控安装助手](docs/wear-installer.md) · [开发与构建](docs/development.md) · [参与开发](CONTRIBUTING.md) · [安全反馈](SECURITY.md) · [第三方说明](THIRD_PARTY_NOTICES.md)

项目原创代码和自绘素材采用 [Apache-2.0](LICENSE)。通信 SDK 和主题素材的来源见 [第三方说明](THIRD_PARTY_NOTICES.md)。

## v0.3.6 下载

手机端为「口袋车控」，手环和手表端为「腕上车控」。各端与安装助手统一为 **0.3.6**（`versionCode` 56）。使用同签名的安装包覆盖旧版可沿用应用数据。

| 设备 | 安装包 |
| --- | --- |
| Android 手机 | [口袋车控 APK](https://github.com/zjs215/geely-band-control/releases/download/v0.3.6/pocket-car-control.apk) |
| 小米手环 10 Pro、支持 Vela RPK 的圆形手表 | [腕上车控完整版 RPK](https://github.com/zjs215/geely-band-control/releases/download/v0.3.6/wrist-car-control.rpk) |
| 小米手环 9 Pro / 8 Pro、手环 10 | [腕上车控 Lite RPK](https://github.com/zjs215/geely-band-control/releases/download/v0.3.6/wrist-car-control-lite.rpk) |
| 支持 APK 侧载的小米 Watch 5 | [Watch 5 APK](https://github.com/zjs215/geely-band-control/releases/download/v0.3.6/wrist-car-control-watch.apk) |
| Android 安装助手（内置手机与穿戴安装包） | [安装助手 APK](https://github.com/zjs215/geely-band-control/releases/download/v0.3.6/wrist-car-control-installer.apk) |

[发布说明](https://github.com/zjs215/geely-band-control/releases/tag/v0.3.6) · [SHA-256 校验文件](https://github.com/zjs215/geely-band-control/releases/download/v0.3.6/SHA256SUMS.txt)

[车控安装助手](docs/wear-installer.md)（朱砂红图标）打开时自动检查 GitHub 最新稳定版并校验 SHA-256（有可用在线更新时可直接查看版本更新内容），也可用内置包离线安装手机 APK、Vela 完整版/Lite RPK 和 Watch 5 APK，支持手环蓝牙直装（含小米手环 10 Pro 空闲会话保活与重新配对恢复）及 Watch 5 无线 ADB 安装。手机端从首页调色盘打开「手机主题 → 桌面图标」，Watch 5 从「设置 → 桌面图标」切换四套图标；Vela 可按图标风格安装 `vela-icon-variants.zip` 中对应的完整版或 Lite RPK。

## 安装与首次验证

1. 在 Android 手机上安装手机 APK（或通过安装助手直接安装）。打开「口袋车控」，选择车辆品牌并[绑定对应品牌的官方小组件](#绑定真实车况)。仅使用米家语音时，可直接按[米家语音直控](#米家语音直控)设置。
2. 使用穿戴端时，先将设备与手机配对，再按设备选择安装包或使用[车控安装助手](docs/wear-installer.md)：
   - **Vela 手环或手表**：可用安装助手通过经典蓝牙直装（需按指引从小米运动健康导出日志以获取 AuthKey），也可通过小米运动健康「我的 → 关于 → Debug → 第三方应用」或 AstroBox 安装对应 RPK。[小米真机安装说明](https://iot.mi.com/vela/quickapp/zh/guide/other/faq.html) · [AstroBox RPK 安装说明](https://astrobox.usea.quest/docs/quick-app-installation.html)
   - **小米 Watch 5**：在手表上开启开发者选项与无线调试，可通过安装助手无线 ADB 安装或手动侧载 Watch 5 APK；也可使用支持 Vela RPK 的版本。Watch 5 APK 通过已配对手机的蓝牙 SPP 直连伴侣 App。
3. 在手机的「穿戴设备」卡片点「启动通信」。使用小米运动健康通道时，按界面提示完成「授权小米设备通信」。使用蓝牙直连 Watch 5 时，按系统提示授予「附近设备」权限。当前连接的设备会显示在这张卡片上。
4. 在手环或手表的应用列表打开「腕上车控」，核对续航和更新时间。点更新时间可刷新，也可以在双屏车控页上拉后松手刷新。

手机端与穿戴端包名均为 `com.garage.bandcontrol`（安装助手包名为 `com.garage.bandcontrol.installer`）。手机和 Vela 穿戴通信需要匹配的签名证书，建议使用同一发布版的安装包配对。[小米设备通信文档](https://iot.mi.com/vela/quickapp/zh/features/network/interconnect.html)

## 主题、图标与手环布局

- **主题与日夜切换**：三端提供「经典」「蓝天与星空」「山脊营地」「城市天际线」「海岸灯塔」「雪山映湖」6 套场景，支持按自定义时间切换、固定日景/夜景，或跟随系统深浅色。
- **独立外观设置**：手机首页调色盘打开「手机主题」，控制手机 App 外观。「穿戴设备 → 穿戴主题」可上下滑动场景滚轮预览日景和夜景，保存后同步给手环和手表；选「跟随手机系统」时，穿戴端在同步车况时收到手机系统对应的日景或夜景。
- **Watch 5 与手环差异**：支持本机主题的穿戴端可在「设置 → 外观主题」选择跟随手机或独立场景，Watch 5 可跟随手表系统；跑道小屏与 Lite 版精简内置壁纸以降低资源占用，使用精简样式或跟随车控状态展示。
- **四套桌面图标**：手机端和 Watch 5 可在应用内切换「紫色软胶（默认）」「黑白几何」「红色复古」「钴蓝折角」四套图标；Vela 可从 `vela-icon-variants.zip` 安装对应风格包。
- **布局与震动**：手机「手环布局」与支持单双屏切换的穿戴端同步。双屏首页显示总续航、油电进度和车辆状态，第二屏显示车控（圆屏/方盘支持 3 按钮横向排版与设置页侧滑返回）；单屏纵向滚动，点车况区域可切换总续航与油电分项。长方形和跑道屏手环的车控按钮单列排列。手机端「车控震动」开关和穿戴端设置页均可控制操作震动提示。

## 绑定真实车况

1. 登录对应品牌的官方 App。在「口袋车控」点击当前品牌的「添加中号小组件」（极氪为「添加极氪大号小组件」），完成 Android 绑定授权。覆盖安装时会沿用已有的第一张有效卡片。
2. 在官方小组件的配置页选中目标车辆和所需按钮（使用吉利银河时，可点卡片右上角齿轮进入配置；领克请在官方 App 中调整桌面组件）。保存后回到「口袋车控」，点卡片更新时间刷新车况。
3. 小组件提供总续航、纯电续航（或电量百分比）、燃油续航、驻车/充电状态和更新时间。当官方卡片仅显示电量百分比而不显示公里数时，穿戴端会自动按百分比同步展示；同一分钟的回传会保留原时间。银河与领克卡片上的未知按钮可在手机预览区直接点按标记。
4. 核对车况和按钮后，在「车控授权」里点「允许车控」。收到「指令已发送」回执后，以官方车况核对执行结果。若穿戴端提示「请在手机确认官方操作」，从「口袋车控」或同名通知打开「处理官方确认」。

从 0.3.6 起，在「车控授权 → 二次确认设置」中选择已识别的动作，可分别设置「手环和手表二次确认」与「米家语音二次确认」。默认穿戴端需要确认，米家语音直接执行。手机和穿戴端都升级到 0.3.6 后，关闭穿戴确认即可单击执行。启用语音确认后，在手机通知或「车控授权」中点「确认执行」，请求 30 秒后失效。设置按品牌和动作保存。官方卡片要求的确认仍按原流程处理。

点手机首页车辆名称或旁边的铅笔图标即可修改名称（最多 10 个字符，默认「我的座驾」）并同步到穿戴端。

刷新、操作结果和连接异常以浮层短暂提示。卡片更新期间，刷新区显示「同步中…」或「请在手机确认」。正在加载的车控按钮保留原位，状态未知时保持禁用，收到可用状态后恢复操作。

单屏手环可点状态区的齿轮进入设置，双屏从车控页底部进入「设置」。设置里可开关「车控震动」、查看最近连接事件；Watch 5 与支持本机设置的端还支持单双屏开关和桌面图标切换。[小米 Vela 振动接口](https://iot.mi.com/vela/quickapp/zh/features/system/vibrator.html)

## 多品牌适配

同一个伴侣 App 与穿戴包支持**吉利银河**、**吉利汽车**、**中国版领克**和**极氪**。全新安装时可选择品牌；点击首页右上角显示官方 App 图标的品牌按钮可随时切换品牌。切换后会重新核对目标品牌卡片，暂挂或清理原待确认动作并关闭米家语音开关，各品牌卡片配置与绑定状态独立保存。

- **吉利银河**（`com.geelygalaxy.customer`，适配 1.59.0 中号卡）：支持 4 个动态功能槽，自动识别并支持补标 11 项功能——**车锁、空调、车窗微开、后备箱、寻车、车窗全开、极速升温、极速降温、净化、除霜、哨兵模式**。车锁、车窗与开关类功能均直接依据官方卡片槽内识别出的图标方向判断动作（例如「解锁／上锁」「窗微开／窗全开／关窗」「开空调／关空调」），实际车辆条件由官方服务端校验；无法识别图标方向时按钮显示暂不可用。实机验证顺序与协议细节见 [腕上车况与车控实现方案](docs/galaxy-app-1.59.0-widget-integration.md)。
- **吉利汽车**（`com.geely.consumer`，适配 3.58.0 中号卡）：同步卡片提供的总续航、油电百分比、状态提示与更新时间；首版自动识别**车锁、空调、寻车**，米家语音使用独立指令码 `GEELY_V1:TEST`、`LOCK`、`UNLOCK`、`CLIMATE`、`FIND`。详情见 [吉利汽车 3.58.0 调研](docs/geely-auto-3.58.0-integration-review.md)。
- **中国版领克**（`com.lynkco.customer`，适配 4.2.9 / 4.3.0 中型卡）：提供 3 个可配置槽位（同时兼容布局中的隐藏节点），按控件描述或已安装领克 App 图标轮廓自动识别**车锁、车窗、后备箱、寻车、发动机、天窗、快速升温、快速降温、充电口**，并支持车主手动核对标记与方向绑定；识别为车锁、车窗或后备箱的按钮可在穿戴端直接切换，伴侣会自动提交紧随派发后 5 秒内、出现在同一卡片且标题匹配的官方确认，其余弹窗在手机处理。语音使用 `LYNKCO_V1` 命名空间，详见 [领克语音](docs/lynkco/voice.md)、[领克调研评审](docs/lynk-integration-review.md) 与 [领克适配说明](docs/lynkco/compatibility.md)。
- **极氪**（`com.zeekrlife.mobile`，适配 5.1.0 大号卡）：同步卡片提供的总续航、油电分项续航、带百分号的电量、充电提示与更新时间；自动识别**车锁、空调、寻车**及部分**后备箱**图标，当前仅执行已自动识别的图标。米家语音目前提供 `ZEEKR_V1:TEST` 连接测试。

> [!NOTE]
> 静态代码、单测与编译通过不代表全部车型及固件在真机环境均已实测通过；不同车型、年款及官方 App 版本的可选功能以官方卡片实际呈现为准。

[Android 小组件托管文档](https://developer.android.com/develop/ui/views/appwidgets/host) · [银河中号卡片的社区接入记录](https://github.com/yangyongyongyong/GeelyPassiveKey/blob/main/docs/79_official_widget_fallback.md)

## 米家语音直控

1. 在手机首页「米家语音」点「通知权限」，为「口袋车控」授予通知使用权，再打开「米家语音」开关（开关默认关闭）。
2. 在「米家语音 → 指令码」点选所需指令（如「测试」或对应功能），查看手动控制引导与示例图后点「复制并打开米家」。在米家「智能 → ＋ → 手动控制」点「添加执行动作」，选择「通知」（手机通知），粘贴完整指令码，将手动控制名称设为对小爱说的口令并保存。入口名称可能随米家版本变化。
3. 在米家点一下测试场景，在口袋车控运行日志里确认收到测试通知。核对官方卡片和车辆后，打开「允许车控」，再为所需动作创建手动场景（无需连接穿戴设备）。
4. 在米家点按场景，或对小爱同学说出场景名触发。伴侣 App 在后台也可接收通知，并按动作设置等待手机确认或直接转交官方卡片。若官方卡片弹出确认，伴侣 App 会提示到手机处理（领克匹配的切换确认可自动提交）。

吉利银河 `GARAGE_V1` 指令表如下；**吉利汽车**使用 `GEELY_V1`（开放 `TEST`、`LOCK`、`UNLOCK`、`CLIMATE`、`FIND`），**领克**使用 `LYNKCO_V1`（开放 `TEST`、`LOCK_TOGGLE`、`WINDOW_TOGGLE`、`TRUNK_TOGGLE`），**极氪**目前开放 `ZEEKR_V1:TEST`，均可在手机端「米家语音 → 指令码」直接查看和复制。

| 动作 | 吉利银河手机通知内容 |
| --- | --- |
| 测试连接 | `GARAGE_V1:TEST` |
| 上锁 | `GARAGE_V1:LOCK` |
| 解锁 | `GARAGE_V1:UNLOCK` |
| 空调 | `GARAGE_V1:CLIMATE` |
| 车窗微开 | `GARAGE_V1:WINDOW_VENT` |
| 后备箱 | `GARAGE_V1:TRUNK` |
| 寻车 | `GARAGE_V1:FIND` |
| 车窗全开 | `GARAGE_V1:WINDOW_OPEN` |
| 极速升温 | `GARAGE_V1:HEAT` |
| 极速降温 | `GARAGE_V1:COOL` |
| 净化 | `GARAGE_V1:PURIFY` |
| 除霜 | `GARAGE_V1:DEFROST` |
| 哨兵模式 | `GARAGE_V1:SENTRY` |

[米家通知来源包名](https://app.mi.com/details?id=com.xiaomi.smarthome) · [Android 通知监听](https://developer.android.com/reference/android/service/notification/NotificationListenerService)

## 后台运行

「后台运行设置」在手机首页的「穿戴设备」上方。打开「省电策略设置」，可跳转到应用信息或 Android 电池优化列表。在应用信息的电池设置中选择「无限制」或开启「允许后台活动」，也可在电池优化列表将「口袋车控」设为「不优化」。返回应用会重读 Android 电池优化状态。

「应用锁定引导」说明如何在最近任务里锁定「口袋车控」。部分小米手机可在「手机管家 → 设置 → 优化加速 → 锁定任务」中开启。系统提供的入口随手机型号和系统版本变化。

[Android 电池优化](https://developer.android.com/training/monitoring-device-state/doze-standby) · [小米应用智能省电](https://dev.mi.com/distribute/doc/details?pId=1628) · [小米后台应用锁定](https://www.mi.com/global/support/faq/details/KA-535435/)

## 手机端运行日志

伴侣在页面底部的「运行日志」集中展示连接变化、通知接收、车控授权、刷新结果和操作提示，支持固定高度内独立滚动查看。最新记录突出显示，异常以琥珀色区分，记录带时间戳；支持一键「复制」完整诊断信息与日志或「清空」。最近 40 条日志保存到本机，重启后仍可查看。

## 本地构建

需要 Windows、JDK 17 或 21、Android SDK Platform 36（含 Build Tools 36.0.0）、Node.js 22 与 npm，以及 OpenSSL（可用 Git for Windows 自带版本）。配置好 `ANDROID_HOME` 或 Android Studio 的 SDK 路径后，在项目根目录运行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\build.ps1
```

需要同时生成三套可选桌面图标的 Vela RPK 变体时，追加 `-IncludeVelaIconVariants`。脚本会取得并校验小米官方 Wearable SDK 的 SHA-256，生成或复用本地签名，运行 Android 单元测试、Lint 与 Vela 回归测试，并在 `artifacts/v<版本>` 下输出配套 APK、RPK 与 `SHA256SUMS.txt`。随后可运行安装助手构建脚本生成内置四端安装包的 `wrist-car-control-installer.apk`：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\build-installer.ps1
```

后续更新请保留 `.local` 中的签名文件，以便覆盖安装并保持手机端与穿戴端证书一致。私钥、口令和构建缓存收在 Git 忽略的目录中。

[小米 SDK 与样例下载入口](https://iot.mi.com/vela/quickapp/zh/features/network/interconnect.html)
