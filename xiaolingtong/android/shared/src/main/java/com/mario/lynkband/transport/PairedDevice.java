package com.mario.lynkband.transport;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public final class PairedDevice {
    private PairedDevice() {}
    public static boolean allowed(Context c) {
        return Build.VERSION.SDK_INT < 31 || c.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }
    public static BluetoothAdapter adapter(Context c) {
        BluetoothManager manager = c.getSystemService(BluetoothManager.class);
        return manager == null ? null : manager.getAdapter();
    }
    @SuppressLint("MissingPermission")
    public static void choose(Activity activity, String title, Consumer<BluetoothDevice> selected) {
        BluetoothAdapter adapter = adapter(activity);
        if (!allowed(activity) || adapter == null || !adapter.isEnabled()) {
            Toast.makeText(activity, "请先开启蓝牙并允许附近设备权限", Toast.LENGTH_LONG).show();
            return;
        }
        List<BluetoothDevice> devices = new ArrayList<>(adapter.getBondedDevices());
        if (devices.isEmpty()) {
            Toast.makeText(activity, "请先在系统中配对手机和手表", Toast.LENGTH_LONG).show();
            return;
        }
        List<String> names = new ArrayList<>();
        for (BluetoothDevice device : devices) {
            String name = device.getName();
            names.add((name == null ? "蓝牙设备" : name) + " · " + device.getAddress().substring(12));
        }
        new AlertDialog.Builder(activity).setTitle(title).setItems(names.toArray(new String[0]),
                (dialog, index) -> selected.accept(devices.get(index))).setNegativeButton("取消", null).show();
    }
}
