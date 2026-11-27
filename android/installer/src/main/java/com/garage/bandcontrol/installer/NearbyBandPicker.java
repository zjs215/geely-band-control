package com.garage.bandcontrol.installer;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/** Discovers devices in the foreground and returns an address only after a user selection. */
@SuppressLint("MissingPermission")
final class NearbyBandPicker {
    private static final long DISCOVERY_LIMIT_MS = 16000;
    private static final long LE_LIMIT_MS = 7000;

    private record Entry(String address, String name, boolean paired, int signal) {
        String label() {
            return (name.isEmpty() ? "未广播名称的设备" : name)
                    + "\n" + address + (paired ? "  ·  已配对" : "");
        }
    }

    private final Activity activity;
    private final BluetoothAdapter bluetooth;
    private final String expectedAddress;
    private final Consumer<String> select;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, Entry> devices = new HashMap<>();
    private final List<Entry> displayed = new ArrayList<>();
    private final TextView help;
    private final ArrayAdapter<String> rows;
    private final AlertDialog dialog;
    private final Runnable endDiscovery = this::startLeFallback;
    private final Runnable endLe = this::finish;
    private boolean discovering;
    private boolean ownsDiscovery;
    private boolean receiverRegistered;
    private boolean closed;
    private BluetoothLeScanner leScanner;
    private ScanCallback leCallback;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!discovering) return;
            if (BluetoothDevice.ACTION_FOUND.equals(intent.getAction())) {
                BluetoothDevice device = discoveredDevice(intent);
                if (device != null) {
                    add(device, intent.getStringExtra(BluetoothDevice.EXTRA_NAME),
                            intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE));
                }
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(intent.getAction())) {
                startLeFallback();
            }
        }
    };

    NearbyBandPicker(Activity activity, BluetoothAdapter bluetooth, String expectedAddress, Consumer<String> select) {
        this.activity = activity;
        this.bluetooth = bluetooth;
        this.expectedAddress = expectedAddress;
        this.select = select;

        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(20), dp(10), dp(20), 0);
        help = new TextView(activity);
        panel.addView(help);
        ListView list = new ListView(activity);
        rows = new ArrayAdapter<>(activity, android.R.layout.simple_list_item_1);
        list.setAdapter(rows);
        panel.addView(list, new LinearLayout.LayoutParams(-1, dp(156)));
        list.setOnItemClickListener((parent, view, index, id) -> {
            String address = displayed.get(index).address();
            close();
            select.accept(address);
        });
        dialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.band_nearby)
                .setView(panel)
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton(R.string.band_scan_again, null)
                .create();
        dialog.setOnDismissListener(ignored -> {
            closed = true;
            stopScanning();
        });
    }

    void show() {
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> start());
        start();
    }

    void close() {
        closed = true;
        stopScanning();
        if (dialog.isShowing()) dialog.dismiss();
    }

    private void start() {
        if (closed) return;
        stopScanning();
        devices.clear();
        render();
        help.setText(R.string.band_scan_running);
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(false);
        discovering = true;
        IntentFilter actions = new IntentFilter(BluetoothDevice.ACTION_FOUND);
        actions.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        try {
            if (Build.VERSION.SDK_INT >= 33) activity.registerReceiver(receiver, actions, Context.RECEIVER_EXPORTED);
            else activity.registerReceiver(receiver, actions);
            receiverRegistered = true;
            if (!bluetooth.isDiscovering()) ownsDiscovery = bluetooth.startDiscovery();
            if (ownsDiscovery || bluetooth.isDiscovering()) handler.postDelayed(endDiscovery, DISCOVERY_LIMIT_MS);
            else startLeFallback();
        } catch (SecurityException denied) {
            finish();
            help.setText("请允许附近设备权限后再搜索。");
        }
    }

    private void startLeFallback() {
        stopDiscovery();
        if (closed || devices.values().stream().anyMatch(entry -> rank(entry) < 2)) {
            finish();
            return;
        }
        try {
            leScanner = bluetooth.getBluetoothLeScanner();
            if (leScanner == null) {
                finish();
                return;
            }
            leCallback = new ScanCallback() {
                @Override public void onScanResult(int type, ScanResult result) {
                    if (leCallback != this || closed) return;
                    String name = result.getScanRecord() == null ? null : result.getScanRecord().getDeviceName();
                    add(result.getDevice(), name, result.getRssi());
                }

                @Override public void onScanFailed(int error) {
                    if (leCallback == this) finish();
                }
            };
            leScanner.startScan(leCallback);
            handler.postDelayed(endLe, LE_LIMIT_MS);
        } catch (SecurityException | IllegalStateException unavailable) {
            finish();
        }
    }

    private void finish() {
        stopScanning();
        if (closed) return;
        help.setText(displayed.isEmpty() ? R.string.band_scan_empty : R.string.band_scan_found);
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(true);
    }

    private void stopScanning() {
        stopDiscovery();
        handler.removeCallbacks(endLe);
        ScanCallback active = leCallback;
        leCallback = null;
        if (active != null && leScanner != null) {
            try { leScanner.stopScan(active); }
            catch (SecurityException | IllegalStateException ignored) { /* Bluetooth was turned off. */ }
        }
        leScanner = null;
    }

    private void stopDiscovery() {
        handler.removeCallbacks(endDiscovery);
        discovering = false;
        if (receiverRegistered) {
            activity.unregisterReceiver(receiver);
            receiverRegistered = false;
        }
        if (ownsDiscovery) {
            ownsDiscovery = false;
            try { bluetooth.cancelDiscovery(); }
            catch (SecurityException ignored) { /* Permission may have been revoked. */ }
        }
    }

    private void add(BluetoothDevice device, String advertisedName, int signal) {
        try {
            String address = WearableLog.address(device.getAddress());
            if (address.isEmpty()) return;
            Entry previous = devices.get(address);
            String name = advertisedName;
            if (name == null || name.trim().isEmpty()) name = device.getName();
            if (name == null || name.trim().isEmpty()) name = previous == null ? "" : previous.name();
            devices.put(address, new Entry(address, name.trim(), device.getBondState() == BluetoothDevice.BOND_BONDED,
                    signal == Short.MIN_VALUE && previous != null ? previous.signal() : signal));
            render();
        } catch (SecurityException denied) {
            finish();
        }
    }

    private void render() {
        displayed.clear();
        displayed.addAll(devices.values());
        displayed.sort(Comparator.comparingInt(this::rank)
                .thenComparing(Comparator.comparingInt(Entry::signal).reversed())
                .thenComparing(Entry::address));
        rows.setNotifyOnChange(false);
        rows.clear();
        for (Entry entry : displayed) rows.add(entry.label());
        rows.notifyDataSetChanged();
    }

    private int rank(Entry entry) {
        if (entry.address().equals(expectedAddress)) return 0;
        String name = entry.name().toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        return (name.contains("手环") || name.contains("band")) && name.contains("10pro") ? 1 : 2;
    }

    @SuppressWarnings("deprecation")
    private static BluetoothDevice discoveredDevice(Intent intent) {
        return Build.VERSION.SDK_INT >= 33
                ? intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class)
                : intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
