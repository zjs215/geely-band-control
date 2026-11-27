package com.otrong.carcontrol.installer;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

public class BandDiagnosticsTest {
    @Rule public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void exportsOnlyStructuredFieldsAfterReopening() throws Exception {
        File storage = folder.newFolder();
        BandDiagnostics first = new BandDiagnostics(storage, "0.1.8", 36, "Android", "QA");
        assertFalse(first.hasEvents());
        BandDiagnostics.Trace trace = first.begin();
        trace.event("connect.request", "one_tap", false, "example_secret", "private-auth-value");
        trace.failure("install.failed", new IOException("Broken pipe private-exception-detail"),
                "fragment", 3, "target", InstallTarget.BAND);

        BandDiagnostics reopened = new BandDiagnostics(storage, "0.1.8", 36, "Android", "QA");
        String exported = new String(reopened.snapshot(), StandardCharsets.UTF_8);
        assertTrue(exported.contains("installer=0.1.8 android_api=36"));
        assertTrue(exported.contains("connect.request one_tap=false"));
        assertTrue(exported.contains("fragment=3 target=BAND errors=IOException/broken_pipe"));
        assertFalse(exported.contains("private-auth-value"));
        assertFalse(exported.contains("private-exception-detail"));
    }

    @Test
    public void retainsRecentEventsWithinTwoSegments() throws Exception {
        BandDiagnostics diagnostics = new BandDiagnostics(folder.newFolder(), "0.1.8", 36, "Android", "QA");
        BandDiagnostics.Trace trace = diagnostics.begin();
        trace.event("test.oldest");
        for (int index = 0; index < 2200; index++) {
            trace.event("transfer.window_send", "part", index, "attempt", 1, "frames", 18);
        }
        trace.event("test.latest");
        byte[] data = diagnostics.snapshot();
        String exported = new String(data, StandardCharsets.UTF_8);
        assertTrue(data.length < 132_000);
        assertFalse(exported.contains("test.oldest"));
        assertTrue(exported.contains("test.latest"));
    }

    @Test
    public void recordsReadEofAndFailedFrameSequenceWithoutFrameData() throws Exception {
        BandDiagnostics diagnostics = new BandDiagnostics(folder.newFolder(), "0.1.8", 36, "Android", "QA");
        OutputStream failing = new OutputStream() {
            @Override public void write(int value) throws IOException { throw new IOException("Broken pipe"); }
        };
        try (BandWire wire = new BandWire(new ByteArrayInputStream(new byte[0]), failing, diagnostics.begin())) {
            assertThrows(EOFException.class, () -> wire.receive(System.nanoTime() + TimeUnit.SECONDS.toNanos(3)));
            byte[] payload = "PRIVATE_PAYLOAD".getBytes(StandardCharsets.UTF_8);
            assertThrows(IOException.class, () -> wire.write(BandWire.encode(BandWire.DATA, 99, payload)));
        }
        String exported = new String(diagnostics.snapshot(), StandardCharsets.UTF_8);
        assertTrue(exported.contains("wire.read_failed"));
        assertTrue(exported.contains("errors=EOFException/eof"));
        assertTrue(exported.contains("type=3 seq=99 errors=IOException/broken_pipe"));
        assertFalse(exported.contains("PRIVATE_PAYLOAD"));
    }
}
