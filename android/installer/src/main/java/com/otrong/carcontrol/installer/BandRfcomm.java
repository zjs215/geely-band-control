package com.otrong.carcontrol.installer;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

final class BandRfcomm {
    private static final UUID SERIAL_PORT = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb");
    // The Band 10 Pro Vela transport uses RFCOMM channel 5.
    private static final int BAND_CHANNEL = 5;

    enum Route {
        DIRECT_SECURE, DIRECT_COMPAT, SPP_SECURE, SPP_COMPAT;

        boolean isDirect() {
            return this == DIRECT_SECURE || this == DIRECT_COMPAT;
        }

        static Route remembered(String value) {
            for (Route route : values()) {
                if (route.name().equals(value)) return route;
            }
            return null;
        }
    }

    record Connected(BluetoothSocket socket, Route route, boolean directRejected) {}

    record RouteOutcome<T>(T value, Route route, boolean directRejected) {}

    interface Attach {
        void socket(BluetoothSocket socket) throws InterruptedException;
    }

    interface Attempt<T> {
        T connect(Route route) throws IOException, InterruptedException;
    }

    static Connected open(BluetoothDevice device, boolean reconnect, Route remembered,
                          ScheduledExecutorService deadlines, Attach attach, BandDiagnostics.Trace trace)
            throws IOException, InterruptedException {
        RouteOutcome<BluetoothSocket> outcome = tryRoutesOutcome(reconnect, remembered, route -> {
            trace.event("rfcomm.try", "route", route);
            try {
                BluetoothSocket socket = openSocket(device, route, deadlines, attach, trace);
                trace.event("rfcomm.connected", "route", route);
                return socket;
            } catch (IOException | InterruptedException | SecurityException failure) {
                trace.failure("rfcomm.failed", failure, "route", route);
                throw failure;
            }
        });
        return new Connected(outcome.value(), outcome.route(), outcome.directRejected());
    }

    static <T> T tryRoutes(boolean reconnect, Route remembered, Attempt<T> attempt)
            throws IOException, InterruptedException {
        return tryRoutesOutcome(reconnect, remembered, attempt).value();
    }

    static <T> RouteOutcome<T> tryRoutesOutcome(boolean reconnect, Route remembered, Attempt<T> attempt)
            throws IOException, InterruptedException {
        List<Route> routes = new ArrayList<>(reconnect ? Arrays.asList(
                Route.DIRECT_SECURE, Route.DIRECT_COMPAT, Route.SPP_SECURE, Route.SPP_COMPAT) : Arrays.asList(
                Route.SPP_SECURE, Route.SPP_COMPAT, Route.DIRECT_SECURE, Route.DIRECT_COMPAT));
        if (remembered != null) {
            routes.remove(remembered);
            routes.add(0, remembered);
        }
        IOException failure = new IOException("未建立手环 RFCOMM 连接");
        boolean directRejected = false;
        for (Route route : routes) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            try {
                return new RouteOutcome<>(attempt.connect(route), route, directRejected);
            } catch (IOException unavailable) {
                if (route.isDirect()) directRejected = true;
                failure.addSuppressed(unavailable);
            }
        }
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        throw failure;
    }

    @SuppressLint("MissingPermission")
    static boolean removeBond(BluetoothDevice device, BandDiagnostics.Trace trace) {
        try {
            Object result = BluetoothDevice.class.getMethod("removeBond").invoke(device);
            boolean removed = Boolean.TRUE.equals(result);
            trace.event("pairing.remove_bond", "accepted", removed);
            return removed;
        } catch (InvocationTargetException rejected) {
            trace.failure("pairing.remove_bond_failed",
                    rejected.getCause() != null ? rejected.getCause() : rejected);
            return false;
        } catch (ReflectiveOperationException | SecurityException unavailable) {
            trace.failure("pairing.remove_bond_failed", unavailable);
            return false;
        }
    }

    @SuppressLint("MissingPermission")
    static boolean createClassicBond(BluetoothDevice device, BandDiagnostics.Trace trace) {
        try {
            Object result = BluetoothDevice.class.getMethod("createBond", int.class)
                    .invoke(device, BluetoothDevice.TRANSPORT_BREDR);
            boolean started = Boolean.TRUE.equals(result);
            trace.event("pairing.create_bond", "transport", BluetoothDevice.TRANSPORT_BREDR, "started", started);
            if (started) return true;
        } catch (InvocationTargetException rejected) {
            trace.failure("pairing.create_classic_bond_failed",
                    rejected.getCause() != null ? rejected.getCause() : rejected);
        } catch (ReflectiveOperationException | SecurityException unavailable) {
            trace.failure("pairing.create_classic_bond_failed", unavailable);
        }
        boolean started = device.createBond();
        trace.event("pairing.create_bond", "transport", BluetoothDevice.TRANSPORT_AUTO, "started", started);
        return started;
    }

    @SuppressLint("MissingPermission")
    private static BluetoothSocket openSocket(BluetoothDevice device, Route route, ScheduledExecutorService deadlines,
                                              Attach attach, BandDiagnostics.Trace trace)
            throws IOException, InterruptedException {
        BluetoothSocket socket = switch (route) {
            case DIRECT_SECURE -> createDirect(device, "createRfcommSocket");
            case DIRECT_COMPAT -> createDirect(device, "createInsecureRfcommSocket");
            case SPP_SECURE -> device.createRfcommSocketToServiceRecord(SERIAL_PORT);
            case SPP_COMPAT -> device.createInsecureRfcommSocketToServiceRecord(SERIAL_PORT);
        };
        AtomicBoolean done = new AtomicBoolean();
        boolean connected = false;
        try {
            attach.socket(socket);
            ScheduledFuture<?> timer = deadlines.schedule(() -> {
                if (done.compareAndSet(false, true)) {
                    trace.event("rfcomm.timeout", "route", route);
                    close(socket);
                }
            }, 8, TimeUnit.SECONDS);
            try {
                socket.connect();
                if (!done.compareAndSet(false, true)) throw new SocketTimeoutException(route.name());
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                connected = true;
                return socket;
            } finally {
                done.set(true);
                timer.cancel(false);
            }
        } finally {
            if (!connected) close(socket);
        }
    }

    private static BluetoothSocket createDirect(BluetoothDevice device, String method) throws IOException {
        try {
            return (BluetoothSocket) BluetoothDevice.class.getMethod(method, int.class).invoke(device, BAND_CHANNEL);
        } catch (InvocationTargetException rejected) {
            if (rejected.getCause() instanceof SecurityException denied) throw denied;
            throw new IOException("设备未能打开 RFCOMM 直连通道", rejected);
        } catch (ReflectiveOperationException | SecurityException unavailable) {
            // OEM and Android versions can limit access to the channel-based method.
            throw new IOException("系统未开放 RFCOMM 直连通道", unavailable);
        }
    }

    private static void close(BluetoothSocket socket) {
        try {
            socket.close();
        } catch (IOException alreadyClosed) {
            // The system may close a failed socket first.
        }
    }
}
