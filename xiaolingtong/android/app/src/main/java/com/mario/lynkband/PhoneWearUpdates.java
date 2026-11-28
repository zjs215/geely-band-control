package com.mario.lynkband;

import android.app.Activity;
import android.bluetooth.BluetoothDevice;
import android.widget.EditText;
import android.text.InputType;
import com.mario.lynkband.transport.PairedDevice;
import com.mario.lynkband.vela.VelaInstaller;
import com.mario.lynkband.vela.VelaAuthKeys;

import com.mario.lynkband.transport.WatchUpdateWire;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class PhoneWearUpdates {
    private static final int PICK_WATCH = 271;
    private static final int PICK_RPK = 272;
    private static final int SAVE_RPK = 273;
    static final String WATCH_PACKAGE = "com.mario.lynkband.watch";
    private static final String VELA_PACKAGE = "com.mario.lynkband.phone";
    private static final String[] MI_FITNESS = {"com.xiaomi.wearable", "com.xiaomi.wearable.global", "com.mi.health"};
    private static final String CACHE_PREFIX = "wear-update-";
    private final Activity activity;
    private final PhoneApkUpdate phoneApk;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private boolean resumed;
    private AlertDialog dialog;
    private Operation operation;
    private PickerResult pendingResult;
    private File exportFile;
    private String exportVersion;
    private String exportName;
    private static final int VELA_PERMISSION = 274;

    PhoneWearUpdates(Activity activity, Bundle saved) {
        this.activity = activity;
        this.phoneApk = new PhoneApkUpdate(activity, saved);
        if (saved == null) return;
        String name = saved.getString("wearExportFile", "");
        if (name.matches("wear-update-[0-9]+\\.tmp")) {
            File file = new File(activity.getCacheDir(), name);
            if (file.isFile() && file.length() > 0 && file.length() <= PhoneWearReleases.MAX_RPK) {
                exportFile = file;
                exportVersion = saved.getString("wearExportVersion", "");
                exportName = saved.getString("wearExportName", "lynk-band.rpk");
            }
        }
    }

    void show(boolean watch) {
        if (!resumed || operation != null) return;
        if (!watch && exportFile != null) { rpkActions(); return; }
        choose(watch ? "选择 Watch APK 来源" : "选择 Vela RPK 来源",
                new String[]{"选择在线版本", "选择本地文件"}, which -> {
                    if (which == 0) releases(watch);
                    else openPackage(watch);
                });
    }

    void showPhone() {
        if (!resumed || operation != null) return;
        if (phoneApk.hasPending()) {
            phoneApk.showInstall();
            return;
        }
        run("手机应用更新", "正在检查手机应用更新…", op -> {
            for (PhoneWearReleases.Release release : PhoneWearReleases.list(op::check)) {
                List<PhoneWearReleases.Asset> assets = PhoneWearReleases.assets(release, op::check);
                for (PhoneWearReleases.Asset asset : assets)
                    if (asset.isPhone()) return new PhoneRelease(release, asset, assets);
            }
            return null;
        }, offered -> {
            if (offered == null) {
                message("手机应用更新", "暂无可用的手机 APK。");
                return;
            }
            String version = offered.release().version();
            if (PhoneWearReleases.compareVersions(version, BuildConfig.VERSION_NAME) < 0) {
                message("手机应用更新", "当前版本 " + BuildConfig.VERSION_NAME + " 已高于最新发布的 " + version + "。");
                return;
            }
            String action = version.equals(BuildConfig.VERSION_NAME) ? "下载并覆盖" : "下载更新";
            display(new AlertDialog.Builder(activity).setTitle("手机应用更新")
                    .setMessage("当前版本 " + BuildConfig.VERSION_NAME + "\n发布版本 " + version
                            + " · " + offered.release().source())
                    .setNegativeButton("取消", null).setPositiveButton(action, (dialog, button) -> downloadPhone(offered)));
        });
    }

    private void downloadPhone(PhoneRelease offered) {
        run("手机应用更新", "正在下载手机 APK…", op -> {
            File file = phoneApk.createPackageFile();
            try {
                writePackage(file, (target, active) -> PhoneWearReleases.download(offered.release(), offered.asset(),
                        offered.assets(), target, active::check,
                        bytes -> active.progress("正在下载并校验手机 APK…", bytes, offered.asset().size())), op);
                String version = offered.release().version();
                phoneApk.verify(file, version);
                op.check();
                return new PhoneApkUpdate.Package(file, version);
            } catch (IOException | RuntimeException error) {
                file.delete();
                throw error;
            }
        }, phoneApk::prepare);
    }

    private void openPackage(boolean watch) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE);
        if (watch) {
            intent.setType("application/vnd.android.package-archive");
        } else {
            // RPKs have no common MIME type across Android file providers.
            intent.setType("*/*").putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                    "application/octet-stream", "application/zip", "application/x-rpk", "application/vnd.quickapp.rpk"
            });
        }
        picker(intent, watch ? PICK_WATCH : PICK_RPK);
    }

    private void releases(boolean watch) {
        run("正在获取在线版本…", op -> PhoneWearReleases.list(op::check), releases -> {
            List<PhoneWearReleases.Release> choices = releases.stream().filter(release -> release.assets() == null
                    || release.assets().stream().anyMatch(asset -> watch ? asset.isWatch() : asset.isRpk()))
                    .collect(java.util.stream.Collectors.toList());
            if (choices.isEmpty()) {
                message("未找到安装包", "这个仓库的最近发布中没有可用的穿戴端安装包。可以改选本地文件。");
                return;
            }
            String[] labels = choices.stream().map(PhoneWearReleases.Release::tag).toArray(String[]::new);
            choose("选择在线版本", labels, which -> listAssets(watch, choices.get(which)));
        });
    }

    private void listAssets(boolean watch, PhoneWearReleases.Release release) {
        run("正在读取发布文件…", op -> PhoneWearReleases.assets(release, op::check), assets -> {
            List<PhoneWearReleases.Asset> choices = assets.stream().filter(asset -> watch ? asset.isWatch() : asset.isRpk())
                    .collect(java.util.stream.Collectors.toList());
            if (choices.isEmpty()) {
                message("没有适用的安装包", "这个版本未附带所选类型的安装包。请返回选择其他版本或本地文件。");
                return;
            }
            String[] labels = choices.stream().map(PhoneWearUpdates::assetLabel).toArray(String[]::new);
            choose(release.tag(), labels, which -> {
                PhoneWearReleases.Asset selected = choices.get(which);
                String download = "正在下载并校验 " + selected.name() + "…";
                PackageSource source = (file, op) -> PhoneWearReleases.download(release, selected, assets, file,
                        op::check, bytes -> op.progress(download, bytes, selected.size()));
                if (watch) watchGuidance(source);
                else stageRpk(source, selected.name());
            });
        });
    }

    private static String assetLabel(PhoneWearReleases.Asset asset) {
        String name = asset.name();
        if (name.startsWith("lynk-band-pill-")) return "跑道屏 · " + name;
        if (name.startsWith("lynk-band-standard-")) return "标准屏 · " + name;
        return asset.isRpk() ? "旧版通用包 · " + name : name;
    }

    private PackageSource local(Uri uri, long limit) {
        return (file, op) -> {
            try (InputStream in = activity.getContentResolver().openInputStream(uri);
                 OutputStream out = new FileOutputStream(file)) {
                if (in == null) throw new UpdateException("无法打开选中的文件。");
                PhoneWearReleases.copy(in, out, limit, -1, op::check,
                        bytes -> op.progress("正在读取本地安装包…", bytes, -1), null);
            }
        };
    }

    private void watchGuidance(PackageSource source) {
        display(new AlertDialog.Builder(activity).setTitle("安装或更新 Watch")
                .setMessage("首次使用请选「首次安装 · ADB」，按提示在手表开启无线调试。\n\n已有小领通请选「蓝牙更新」，在手表的「小领通 → 设置」打开「接收手机更新」，收到安装包后在手表确认。")
                .setNeutralButton("首次安装 · ADB", (d, w) -> adbChoice(source))
                .setNegativeButton("取消", null).setPositiveButton("蓝牙更新", (d, w) -> sendWatch(source)));
    }

    private void adbChoice(PackageSource source) {
        choose("Watch 首次安装 · 无线调试", new String[]{"使用配对码配对", "已与本手机配对，直接连接"},
                which -> adbEndpoint(source, which == 0, ""));
    }

    private void adbEndpoint(PackageSource source, boolean pairing, String addressHint) {
        android.widget.LinearLayout form = new android.widget.LinearLayout(activity);
        form.setOrientation(android.widget.LinearLayout.VERTICAL);
        int margin = Math.round(24 * activity.getResources().getDisplayMetrics().density);
        form.setPadding(margin, 0, margin, 0);
        EditText endpoint = new EditText(activity);
        endpoint.setSingleLine();
        endpoint.setHint(pairing ? "IP 地址:配对端口" : "IP 地址:连接端口");
        endpoint.setText(addressHint);
        endpoint.setSelection(endpoint.length());
        form.addView(endpoint);
        EditText pin = new EditText(activity);
        if (pairing) {
            pin.setSingleLine();
            pin.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
            pin.setSaveEnabled(false);
            pin.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO);
            pin.setHint("六位配对码");
            form.addView(pin);
        }
        AlertDialog prompt = display(new AlertDialog.Builder(activity)
                .setTitle(pairing ? "与 Android 手表配对" : "检查手表并首次安装")
                .setMessage(pairing ? "手机和手表连同一 Wi-Fi。在手表的「开发者选项 → 无线调试」点「使用配对码配对」，输入弹窗中的地址和配对码。"
                        : "输入手表「无线调试」主页上的连接地址。确认手表还没有小领通后，手机才会发送所选 APK。")
                .setView(form).setNegativeButton("取消", null).setPositiveButton(pairing ? "配对" : "检查并安装", null));
        prompt.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String entry = endpoint.getText().toString().trim();
            int split = entry.lastIndexOf(':');
            String host;
            int port;
            try {
                if (split < 1) throw new IOException("请输入 IP 地址和端口");
                host = WatchAdb.localIp(entry.substring(0, split));
                port = WatchAdb.port(entry.substring(split + 1));
            } catch (IOException | IllegalArgumentException invalid) { endpoint.setError(invalid.getMessage()); return; }
            String code = pin.getText().toString();
            if (pairing && !code.matches("[0-9]{6}")) { pin.setError("请输入六位配对码"); return; }
            pin.getText().clear();
            if (pairing) {
                run("Watch 无线调试配对", "正在与手表配对…", op -> withAdb(op, 40, adb -> {
                    if (!adb.pair(host, port, code)) throw new UpdateException("配对失败，请核对配对端口和配对码。");
                    return true;
                }), ok -> adbEndpoint(source, false, entry.substring(0, split + 1)));
            } else installFirstWatch(source, host, port);
        });
    }

    private void installFirstWatch(PackageSource source, String host, int port) {
        run("Watch 首次安装", "正在连接并检查手表…", op -> withAdb(op, 300, adb -> {
            adb.connectWatch(host, port);
            long current = adb.currentVersion();
            if (current > 0) return new FirstInstall(current, "", false);
            op.check();
            File file = File.createTempFile(CACHE_PREFIX, ".apk", activity.getCacheDir());
            try {
                writePackage(file, source, op);
                PackageInfo info = watchPackage(file);
                android.content.pm.Signature[] incoming = info.signingInfo.getApkContentsSigners();
                PackageInfo phone = activity.getPackageManager().getPackageInfo(activity.getPackageName(),
                        PackageManager.GET_SIGNING_CERTIFICATES);
                if (phone.signingInfo == null || !new java.util.HashSet<>(java.util.Arrays.asList(incoming)).equals(
                        new java.util.HashSet<>(java.util.Arrays.asList(phone.signingInfo.getApkContentsSigners()))))
                    throw new UpdateException("Watch APK 签名与小领通手机端不一致。");
                op.check();
                WatchAdb.Result result = adb.installFirst(file, info.getLongVersionCode(),
                        (stage, percent) -> op.progress(stage, Math.max(0, percent), 100));
                return new FirstInstall(0, info.versionName == null ? Long.toString(info.getLongVersionCode()) : info.versionName,
                        result.versionChecked());
            } finally { file.delete(); }
        }), result -> {
            if (result.installedCode() > 0) {
                display(new AlertDialog.Builder(activity).setTitle("手表已安装小领通")
                        .setMessage("当前版本代码（versionCode）" + result.installedCode() + "。如需更新，请在手表设置中打开「接收手机更新」。")
                        .setPositiveButton("转到蓝牙更新", (d, w) -> sendWatch(source)).setNegativeButton("关闭", null));
            } else message(result.verified() ? "Watch 首次安装完成" : "请核对手表版本",
                    result.verified() ? "已从手表读回并核对版本 " + result.version() + "。"
                            : "系统已返回安装成功，版本读回尚未确认。请打开手表上的小领通查看。");
        });
    }

    private interface AdbWork<T> { T apply(WatchAdb adb) throws Exception; }
    private record FirstInstall(long installedCode, String version, boolean verified) {}

    private <T> T withAdb(Operation op, int timeoutSeconds, AdbWork<T> work) throws IOException {
        var timer = Executors.newSingleThreadScheduledExecutor();
        try (WatchAdb adb = new WatchAdb(activity.getApplicationContext())) {
            Runnable close = () -> { try { adb.close(); } catch (IOException ignored) {} };
            long deadline = SystemClock.elapsedRealtime() + timeoutSeconds * 1000L;
            timer.scheduleWithFixedDelay(() -> {
                if (op.cancelled.get() || SystemClock.elapsedRealtime() >= deadline) close.run();
            }, 0, 200, java.util.concurrent.TimeUnit.MILLISECONDS);
            op.check();
            return work.apply(adb);
        } catch (IOException failure) { throw failure; }
        catch (Exception failure) { throw new UpdateException("无线调试未完成，请核对手表的配对码、连接端口和 Wi-Fi。"); }
        finally { timer.shutdownNow(); }
    }

    private PackageInfo watchPackage(File file) throws IOException {
        PackageInfo info = activity.getPackageManager().getPackageArchiveInfo(file.getPath(), PackageManager.GET_SIGNING_CERTIFICATES);
        if (info == null || !WATCH_PACKAGE.equals(info.packageName) || info.signingInfo == null
                || info.signingInfo.getApkContentsSigners() == null || info.signingInfo.getApkContentsSigners().length == 0
                || info.getLongVersionCode() <= 0) throw new UpdateException("请选择有效且已签名的小领通 Watch APK。");
        return info;
    }

    private void sendWatch(PackageSource source) {
        run("正在准备 Watch 安装包…", op -> {
            File packageFile = File.createTempFile(CACHE_PREFIX, ".apk", activity.getCacheDir());
            try {
                writePackage(packageFile, source, op);
                PackageInfo info = watchPackage(packageFile);
                String version = info.versionName == null ? Long.toString(info.getLongVersionCode()) : info.versionName;
                String title = "正在等待手表接收 " + version + "…";
                op.progress(title, 0, packageFile.length());
                try {
                    new WatchApkTransfer(activity).send(packageFile, op.cancelled::get,
                            bytes -> op.progress("正在发送 Watch 更新…", bytes, packageFile.length()));
                } catch (IOException error) {
                    String detail = error.getMessage();
                    throw new UpdateException(detail == null ? "请核对配对状态，并打开手表上的接收更新页面。" : detail);
                }
                return version;
            } finally {
                packageFile.delete();
            }
        }, version -> message("Watch 安装包已发送", "版本 " + version + "。请在手表上核对安装提示并确认。"));
    }

    private void stageRpk(PackageSource source, String releaseName) {
        var current = ((WristApp) activity.getApplication()).currentVelaVersion();
        run("正在准备 Vela 安装包…", op -> {
            File file = File.createTempFile(CACHE_PREFIX, ".tmp", activity.getCacheDir());
            try {
                writePackage(file, source, op);
                RpkInfo metadata = rpkInfo(file);
                if (current != null) checkRpkTarget(metadata, current);
                String version = metadata.version();
                if (releaseName != null && !releaseName.equals("lynk-band.rpk") && !releaseName.endsWith("-" + version + ".rpk")) {
                    throw new UpdateException("安装包中的版本与发布文件名不符。");
                }
                op.check();
                return new Export(file, version, releaseName == null ? metadata.fileName() : releaseName);
            } catch (IOException | RuntimeException error) {
                file.delete();
                throw error;
            }
        }, export -> {
            clearExport();
            exportFile = export.file();
            exportVersion = export.version();
            exportName = export.name();
            rpkActions();
        });
    }

    private void rpkActions() {
        if (exportFile == null) return;
        display(new AlertDialog.Builder(activity).setTitle("安装 Vela 应用")
                .setMessage(exportName + "\n请核对标准屏或胶囊屏。蓝牙直装需要设备支持小米 SPP 或 BLE 安装，并提供该设备的 AuthKey。")
                .setPositiveButton("蓝牙安装", (d, w) -> selectVela())
                .setNeutralButton("保存 RPK", (d, w) -> {
                    if (!picker(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                            .setType("application/octet-stream").putExtra(Intent.EXTRA_TITLE, exportName), SAVE_RPK)) clearExport();
                }).setNegativeButton("取消", (d, w) -> clearExport()).setOnCancelListener(d -> clearExport()));
    }

    boolean velaPermission(int code) {
        if (code != VELA_PERMISSION) return false;
        if (PairedDevice.allowed(activity)) selectVela();
        else message("需要附近设备权限", "可在系统设置中允许小领通连接附近设备，或选择保存 RPK。");
        return true;
    }

    private boolean askedVelaPermissions;

    private void selectVela() {
        if (exportFile == null) return;
        String[] permissions = android.os.Build.VERSION.SDK_INT >= 31
                ? new String[]{android.Manifest.permission.BLUETOOTH_SCAN, android.Manifest.permission.BLUETOOTH_CONNECT}
                : new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION};
        for (String permission : permissions) {
            if (!askedVelaPermissions && activity.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                askedVelaPermissions = true;
                activity.requestPermissions(permissions, VELA_PERMISSION);
                return;
            }
        }
        closeDialog();
        dialog = com.mario.lynkband.vela.VelaDevicePicker.show(activity, this::confirmVela);
    }

    @android.annotation.SuppressLint("MissingPermission")
    private void confirmVela(BluetoothDevice device) {
        if (exportFile == null) return;
        String address = device.getAddress();
        EditText input = new EditText(activity);
        input.setSingleLine();
        input.setSaveEnabled(false);
        input.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setHint("32 位十六进制 AuthKey");
        byte[] savedKey = VelaAuthKeys.load(activity, address);
        boolean hasSavedKey = savedKey != null;
        if (savedKey != null) {
            input.setText(VelaAuthKeys.format(savedKey));
            input.setSelection(input.length());
            java.util.Arrays.fill(savedKey, (byte) 0);
        }
        android.widget.LinearLayout form = new android.widget.LinearLayout(activity);
        form.setOrientation(android.widget.LinearLayout.VERTICAL);
        android.widget.Spinner protocol = new android.widget.Spinner(activity);
        protocol.setAdapter(new android.widget.ArrayAdapter<>(activity, android.R.layout.simple_spinner_dropdown_item,
                VelaInstaller.Mode.values()));
        form.addView(protocol);
        form.addView(input);
        AlertDialog.Builder builder = new AlertDialog.Builder(activity).setTitle("确认蓝牙安装")
                .setMessage((device.getName() == null ? "Vela" : device.getName()) + " · " + address
                        + "\n" + exportName + "\n"
                        + (hasSavedKey ? "已带出本机加密保存的 AuthKey，可直接安装。" : "请输入这台设备的绑定密钥，验证后将加密保存到本机。"))
                .setView(form).setNegativeButton("返回", (d, w) -> rpkActions()).setPositiveButton("安装", null);
        if (hasSavedKey) {
            builder.setNeutralButton("清除已保存密钥", (d, w) -> {
                VelaAuthKeys.clear(activity, address);
                input.getText().clear();
                Toast.makeText(activity, "已清除这台设备的 AuthKey", Toast.LENGTH_SHORT).show();
            });
        }
        AlertDialog prompt = display(builder);
        prompt.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            byte[] key;
            try { key = VelaInstaller.key(input.getText().toString()); }
            catch (IllegalArgumentException invalid) { input.setError("请输入有效的 32 位十六进制密钥"); return; }
            input.getText().clear();
            File file = exportFile;
            String version = exportVersion;
            VelaInstaller.Mode mode = (VelaInstaller.Mode) protocol.getSelectedItem();
            var current = ((WristApp) activity.getApplication()).currentVelaVersion();
            run("Vela 蓝牙安装", "正在连接所选 Vela 设备…", op -> {
                try {
                    RpkInfo info = rpkInfo(file);
                    if (current != null) checkRpkTarget(info, current);
                    return VelaInstaller.send(activity, device, mode, key, file, info.versionCode(), op.cancelled::get,
                            new VelaInstaller.Progress() {
                                @Override public void report(String stage, int percent) {
                                    op.progress(stage, Math.max(0, percent), 100);
                                }
                                @Override public void authenticated() {
                                    try {
                                        VelaAuthKeys.save(activity, address, key);
                                    } catch (java.security.GeneralSecurityException | RuntimeException unavailable) {
                                        activity.runOnUiThread(() -> Toast.makeText(activity,
                                                "已通过设备认证，本机未能保存密钥", Toast.LENGTH_LONG).show());
                                    }
                                }
                            });
                } finally {
                    java.util.Arrays.fill(key, (byte) 0);
                }
            }, confirmed -> {
                clearExport();
                message(confirmed ? "Vela 已确认安装" : "RPK 已发送",
                    confirmed ? "设备已确认安装小领通 " + version + "。" : "设备尚未返回安装结果，请在穿戴设备上核对版本 " + version + "。");
            });
        });
    }

    private void writePackage(File file, PackageSource source, Operation operation) throws IOException {
        operation.check();
        source.copy(file, operation);
        operation.check();
        if (file.length() == 0) throw new UpdateException("安装包为空。");
    }

    static String rpkVersion(File file) throws IOException {
        return rpkInfo(file).version();
    }

    static RpkInfo rpkInfo(File file) throws IOException {
        try (ZipFile archive = new ZipFile(file)) {
            ZipEntry manifest = archive.getEntry("manifest.json");
            ZipEntry cert = archive.getEntry("META-INF/CERT");
            if (manifest == null || manifest.isDirectory() || cert == null || cert.isDirectory() || cert.getSize() <= 0) {
                throw new UpdateException("请选择带清单和签名信息的小领通 Vela RPK。");
            }
            ByteArrayOutputStream data = new ByteArrayOutputStream();
            try (InputStream in = archive.getInputStream(manifest)) {
                PhoneWearReleases.copy(in, data, 64 * 1024L, manifest.getSize(), () -> {}, bytes -> {}, null);
            }
            JSONObject info = new JSONObject(new String(data.toByteArray(), StandardCharsets.UTF_8));
            String version = info.optString("versionName");
            int versionCode = info.opt("versionCode") instanceof Integer value ? value : 0;
            if (!VELA_PACKAGE.equals(info.optString("package")) || !version.matches("[0-9]{1,9}\\.[0-9]{1,9}\\.[0-9]{1,9}")
                    || versionCode <= 0) {
                throw new UpdateException("RPK 的应用包名或版本不符合预期。");
            }
            String shape = switch (info.optString("icon")) {
                case "/common/launcher-round.png" -> "pill-";
                case "/common/launcher-pro.png" -> "standard-";
                default -> "";
            };
            String profile = shape.isEmpty() ? "" : shape.substring(0, shape.length() - 1);
            return new RpkInfo(version, versionCode, profile, "lynk-band-" + shape + version + ".rpk");
        } catch (JSONException | java.util.zip.ZipException badArchive) {
            throw new UpdateException("无法读取此 Vela RPK 的安装包清单。");
        }
    }

    static void checkRpkTarget(RpkInfo candidate, WristApp.VelaVersion current) throws IOException {
        if (current == null) throw new UpdateException("请先打开要更新的 Vela 设备上的小领通，并保持连接。");
        if (!candidate.profile().equals(current.profile()))
            throw new UpdateException("RPK 屏型与当前设备的安装包不匹配。");
        if (!WatchUpdateWire.versionAllowed(current.code(), candidate.versionCode()))
            throw new UpdateException("RPK 版本低于当前 Vela 版本，不能降级。");
    }

    boolean activityResult(int request, int result, Intent data) {
        if (phoneApk.activityResult(request, result)) return true;
        if (request != PICK_WATCH && request != PICK_RPK && request != SAVE_RPK) return false;
        Uri uri = result == Activity.RESULT_OK && data != null ? data.getData() : null;
        pendingResult = new PickerResult(request, uri != null && "content".equals(uri.getScheme()) ? uri : null);
        if (resumed) handleResult();
        return true;
    }

    private void handleResult() {
        PickerResult result = pendingResult;
        pendingResult = null;
        if (result == null) return;
        if (result.request() == SAVE_RPK) {
            File file = exportFile;
            String version = exportVersion;
            exportFile = null;
            exportVersion = null;
            if (file != null && result.uri() != null) saveRpk(file, version, result.uri());
            else if (file != null) file.delete();
            else if (result.uri() != null) {
                discardDocument(result.uri());
                message("导出已中断", "请重新选择 Vela 安装包。");
            }
        } else if (result.uri() != null) {
            if (result.request() == PICK_WATCH) watchGuidance(local(result.uri(), PhoneWearReleases.MAX_WATCH_APK));
            else stageRpk(local(result.uri(), PhoneWearReleases.MAX_RPK), null);
        }
    }

    private void saveRpk(File file, String version, Uri destination) {
        var current = ((WristApp) activity.getApplication()).currentVelaVersion();
        run("正在保存 Vela 安装包…", op -> {
            boolean saved = false;
            try {
                if (current != null) checkRpkTarget(rpkInfo(file), current);
                try (InputStream in = new java.io.FileInputStream(file);
                     OutputStream out = activity.getContentResolver().openOutputStream(destination, "wt")) {
                    if (out == null) throw new UpdateException("无法写入所选保存位置。");
                    PhoneWearReleases.copy(in, out, PhoneWearReleases.MAX_RPK, file.length(), op::check,
                            bytes -> op.progress("正在保存 Vela 安装包…", bytes, file.length()), null);
                }
                saved = true;
            } finally {
                file.delete();
                if (!saved) discardDocument(destination);
            }
            return version;
        }, done -> {
            AlertDialog.Builder success = new AlertDialog.Builder(activity).setTitle("Vela 安装包已保存")
                    .setMessage("版本 " + done + "。请在小米运动健康或设备兼容的 RPK 安装工具中打开第三方应用安装入口，选中刚保存的文件。入口取决于设备和固件。");
            Intent fitness = fitnessLaunch();
            if (fitness != null) success.setNegativeButton("关闭", null).setPositiveButton("打开小米运动健康", (d, w) -> start(fitness));
            else success.setPositiveButton("知道了", null);
            display(success);
        });
    }

    private void discardDocument(Uri document) {
        try {
            if (DocumentsContract.isDocumentUri(activity, document)) DocumentsContract.deleteDocument(activity.getContentResolver(), document);
        } catch (IOException | RuntimeException ignored) {
            // The provider may not allow deleting a document that it created.
        }
    }

    private Intent fitnessLaunch() {
        for (String packageName : MI_FITNESS) {
            Intent launch = activity.getPackageManager().getLaunchIntentForPackage(packageName);
            if (launch != null) return launch;
        }
        return null;
    }

    void resume() {
        resumed = true;
        handleResult();
        phoneApk.resume();
    }

    void stop() {
        resumed = false;
        cancel();
        closeDialog();
    }

    void save(Bundle state) {
        phoneApk.save(state);
        if (exportFile != null) {
            state.putString("wearExportFile", exportFile.getName());
            state.putString("wearExportVersion", exportVersion);
            state.putString("wearExportName", exportName);
        }
    }

    void destroy() {
        stop();
        executor.shutdown();
        if (activity.isFinishing()) {
            clearExport();
            phoneApk.discard();
        }
    }

    private void clearExport() {
        if (exportFile != null) exportFile.delete();
        exportFile = null;
        exportVersion = null;
    }

    private boolean picker(Intent intent, int request) {
        try {
            activity.startActivityForResult(intent, request);
            return true;
        } catch (ActivityNotFoundException | SecurityException unavailable) {
            message("无法打开文件选择器", "此设备没有可用的系统文件选择器。");
            return false;
        }
    }

    private void start(Intent intent) {
        try { activity.startActivity(intent); }
        catch (ActivityNotFoundException | SecurityException unavailable) {
            Toast.makeText(activity, "无法打开小米运动健康。请手动打开安装工具。", Toast.LENGTH_LONG).show();
        }
    }

    private void choose(String title, String[] choices, Consumer<Integer> chosen) {
        display(new AlertDialog.Builder(activity).setTitle(title)
                .setItems(choices, (d, which) -> chosen.accept(which)).setNegativeButton("取消", null));
    }

    private void message(String title, String body) {
        display(new AlertDialog.Builder(activity).setTitle(title).setMessage(body).setPositiveButton("知道了", null));
    }

    private AlertDialog display(AlertDialog.Builder builder) {
        closeDialog();
        dialog = builder.show();
        return dialog;
    }

    private void closeDialog() {
        if (dialog != null) {
            dialog.dismiss();
            dialog = null;
        }
    }

    private <T> void run(String status, Work<T> work, Consumer<T> success) {
        run("穿戴端更新", status, work, success);
    }

    private <T> void run(String title, String status, Work<T> work, Consumer<T> success) {
        if (!resumed || operation != null) return;
        Operation op = new Operation();
        operation = op;
        AlertDialog progress = display(new AlertDialog.Builder(activity).setTitle(title)
                .setMessage(status).setNegativeButton("取消", (d, w) -> cancel()).setOnCancelListener(d -> cancel()));
        progress.setCanceledOnTouchOutside(false);
        android.view.Window window = progress.getWindow();
        if (window != null) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        executor.execute(() -> {
            try {
                T value = work.run(op);
                activity.runOnUiThread(() -> {
                    if (complete(op)) success.accept(value);
                    else if (value instanceof Export export) export.file().delete();
                    else if (value instanceof PhoneApkUpdate.Package apk) apk.file().delete();
                });
            } catch (IOException | RuntimeException error) {
                activity.runOnUiThread(() -> {
                    if (complete(op)) {
                        String detail = error.getMessage();
                        // Network and document exceptions can contain private or temporary URLs.
                        if (detail == null || !detail.matches("[\u3400-\u9fffA-Za-z0-9 .，。！？、：「」（）()…→+_-]{1,240}")) {
                            detail = "未能完成操作。请检查网络、安装包或保存位置后重试。";
                        }
                        message(title + "未完成", detail);
                    }
                });
            }
        });
    }

    private boolean complete(Operation current) {
        if (!resumed || operation != current) return false;
        operation = null;
        closeDialog();
        return true;
    }

    private void cancel() {
        if (operation != null) {
            operation.cancelled.set(true);
            operation = null;
        }
    }

    private final class Operation {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private long lastProgress = -1_000;

        void check() throws IOException {
            if (cancelled.get()) throw new UpdateException("操作已取消。");
        }

        void progress(String stage, long bytes, long total) {
            long now = SystemClock.elapsedRealtime();
            if (bytes != 0 && bytes != total && now - lastProgress < 250) return;
            lastProgress = now;
            String size = bytes == 0 || total <= 0 ? (bytes == 0 ? "" : "\n" + (bytes / 1024) + " KiB")
                    : "\n" + (100 * bytes / total) + "%";
            activity.runOnUiThread(() -> {
                if (resumed && operation == this && dialog != null) dialog.setMessage(stage + size);
            });
        }
    }

    record RpkInfo(String version, int versionCode, String profile, String fileName) {}
    private record PhoneRelease(PhoneWearReleases.Release release, PhoneWearReleases.Asset asset,
                                List<PhoneWearReleases.Asset> assets) {}
    private record PickerResult(int request, Uri uri) {}
    private record Export(File file, String version, String name) {}
    private interface Work<T> { T run(Operation op) throws IOException; }
    private interface PackageSource { void copy(File file, Operation op) throws IOException; }
    private static final class UpdateException extends IOException {
        UpdateException(String message) { super(message); }
    }
}
