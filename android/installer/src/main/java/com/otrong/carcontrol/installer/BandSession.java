package com.otrong.carcontrol.installer;

import android.bluetooth.BluetoothSocket;

import java.io.IOException;

/** The authenticated socket used for a single installation attempt. */
final class BandSession implements AutoCloseable {
    final String address;
    final BluetoothSocket socket;
    final BandRfcomm.Route route;
    final boolean directRejected;
    final BandProtocol protocol;
    final BandDiagnostics.Trace trace;
    boolean keepAlivePending;
    volatile boolean keepAliveEnabled;
    private final BandWire wire;

    BandSession(String address, BluetoothSocket socket, BandRfcomm.Route route, boolean directRejected,
                BandDiagnostics.Trace trace)
            throws IOException {
        this.address = address;
        this.socket = socket;
        this.route = route;
        this.directRejected = directRejected;
        this.trace = trace;
        wire = new BandWire(socket.getInputStream(), socket.getOutputStream(), trace);
        protocol = new BandProtocol(wire, trace);
    }

    boolean isOpen() {
        return socket.isConnected() && wire.isOpen();
    }

    @Override
    public void close() {
        keepAliveEnabled = false;
        trace.event("session.close", "socket_connected", socket.isConnected(), "wire_open", wire.isOpen());
        wire.close();
        try {
            socket.close();
        } catch (IOException alreadyClosed) {
            // The underlying link can also be closed by the system.
        } finally {
            protocol.close();
        }
    }
}
