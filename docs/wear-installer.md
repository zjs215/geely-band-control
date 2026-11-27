# 腕上银河安装助手

把 `galaxy-wear-installer.apk` 安装到 Android 手机。内置包在构建时选定，手机在安装前核对 SHA-256。手环和手表装好后，通过「智在银河」核对连接和车况。手机端「智在银河」按项目原来的方式安装。

## 小米手环 10 Pro

1. 在小米运动健康连接手环并同步一次。进入「我的 → 关于」，连续点击页面顶部的应用图标，按提示导出或迁移日志。ZIP 通常保存在「下载 → wearablelog」，入口和日志内容取决于运动健康版本。
2. 在助手点「授权日志目录以自动读取」，在系统选择器中进入 `wearablelog` 并授权。第一次点「搜索附近的手环」，在手环上打开「设置 → 我的设备 → 连接新手机」并保持亮屏，在助手里选择扫到的 10 Pro。已有系统配对时，也可直接从「选择已配对手环」选取。
3. 点「安装到手环」，助手读取最新 ZIP 并按地址匹配 AuthKey。若系统发起蓝牙配对，在手机和手环上按提示确认。换绑后重新导出日志。
4. 助手发送**完整版** `galaxy-vela-band.rpk`。蓝牙连接被占用时，可先在手机系统应用信息里暂时停止小米运动健康，再发起安装。
5. 等待手环返回安装回执。助手显示「已传完」时，先在手环应用列表确认。已有旧版时，在应用内核对版本。再打开小米运动健康恢复连接，到手环上的腕上银河刷新车况。

单次使用可点「导入运动健康日志 ZIP」。也可在 ZIP 的运动健康日志中查看对应手环的 `encryptKey` 字段，在助手内手动填写 32 位十六进制值。 [日志导出参考](https://github.com/HyperionD/minstall/tree/v0.2.0)

日志在手机本地读取。助手保存目录的只读授权和上次选定的蓝牙地址，AuthKey 只保留在本次运行的内存中。覆盖安装沿用穿戴应用的原签名，Vela 端与手机端使用同包名、同证书的配套包。

## 小米 Watch 5

1. 在手表上开启开发者选项和无线调试，把手机和手表连到同一 Wi-Fi。
2. 首次使用，在表上打开「使用配对码配对」。把该页面的 IP、配对端口和六位码填到助手，点「配对手表」。
3. 回到手表的无线调试首页，用该页显示的**连接端口**点「安装到手表」。助手通过无线 ADB 发送 `galaxy-watch.apk` 并回读包版本。
4. 在手表上启动腕上银河并核对手机蓝牙通信。安装完可在手表上关闭无线调试。

手机在私有存储中加密保存自己的 ADB 配对身份。手表端可以通过无线调试页面移除已配对的助手。具体菜单和连接能力取决于手表固件。

## 构建

先准备 `artifacts/galaxy-vela-band.rpk`、`artifacts/galaxy-watch.apk` 及对应的 `artifacts/SHA256SUMS.txt`，然后从项目根目录运行。

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\build-installer.ps1
```

`-PackageDirectory` 可指向保存上述三份文件的其他目录。脚本检查完整版 RPK 的主题资源和包名、Watch APK 的目标声明和入口，以及两个包的 SHA-256，然后构建、测试并输出 `artifacts/galaxy-wear-installer.apk`。当前构建渠道使用本机 Android 调试签名。完整的蓝牙及无线安装流程需用对应真机核验。

手环路径参考 [minstall v0.2.0](https://github.com/HyperionD/minstall/tree/v0.2.0)。手表路径使用 [libadb-android-bc 3.5.1](https://github.com/osservatorionessuno/libadb-android-bc/tree/3.5.1)。
