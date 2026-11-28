package com.mario.lynkband.vela;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.*;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.pm.PackageManager;
import android.os.*;
import android.widget.ArrayAdapter;

import com.mario.lynkband.transport.PairedDevice;
import java.util.*;
import java.util.function.Consumer;

@SuppressLint("MissingPermission")
public final class VelaDevicePicker {
    private VelaDevicePicker() {}

    public static AlertDialog show(Activity activity, Consumer<BluetoothDevice> select) {
        BluetoothAdapter adapter = PairedDevice.adapter(activity);
        if (!PairedDevice.allowed(activity) || adapter == null || !adapter.isEnabled())
            return new AlertDialog.Builder(activity).setMessage("请开启手机蓝牙并允许附近设备权限。").setPositiveButton("关闭", null).show();
        List<BluetoothDevice> devices = new ArrayList<>();
        ArrayAdapter<String> names = new ArrayAdapter<>(activity, android.R.layout.simple_list_item_1);
        Consumer<BluetoothDevice> add = device -> {
            if (devices.contains(device)) return;
            devices.add(device);
            String name = device.getName();
            names.add((name == null ? "蓝牙设备" : name) + " · " + device.getAddress().substring(12));
        };
        for (BluetoothDevice device : adapter.getBondedDevices()) add.accept(device);
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle("选择 Vela 设备")
                .setAdapter(names, (d, i) -> select.accept(devices.get(i))).setNegativeButton("取消", null).show();
        String permission = Build.VERSION.SDK_INT >= 31 ? Manifest.permission.BLUETOOTH_SCAN : Manifest.permission.ACCESS_FINE_LOCATION;
        BluetoothLeScanner scanner = adapter.getBluetoothLeScanner();
        if (scanner == null || activity.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            dialog.setTitle(devices.isEmpty() ? "请配对设备或允许扫描附近设备" : "选择已配对设备");
            return dialog;
        }
        Handler handler = new Handler(Looper.getMainLooper());
        ScanCallback callback = new ScanCallback() {
            @Override public void onScanResult(int type, ScanResult result) {
                if (dialog.isShowing()) add.accept(result.getDevice());
            }
            @Override public void onScanFailed(int error) {
                if (dialog.isShowing()) dialog.setTitle("附近扫描失败（" + error + "）");
            }
        };
        ScanFilter filter = new ScanFilter.Builder().setServiceUuid(ParcelUuid.fromString("0000fe95-0000-1000-8000-00805f9b34fb")).build();
        Runnable stop = () -> {
            try { scanner.stopScan(callback); } catch (IllegalStateException | SecurityException ignored) { }
        };
        Runnable finish = () -> {
            stop.run();
            if (dialog.isShowing()) dialog.setTitle(devices.isEmpty() ? "未发现设备，请唤醒手表再试" : "选择 Vela 设备");
        };
        dialog.setOnDismissListener(d -> { handler.removeCallbacks(finish); stop.run(); });
        try {
            scanner.startScan(Collections.singletonList(filter), new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback);
            handler.postDelayed(finish, 12000);
        } catch (IllegalStateException | SecurityException denied) { dialog.setTitle("附近扫描不可用，请选择已配对设备"); }
        return dialog;
    }
}
