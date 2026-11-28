package com.mario.lynkband.vela;

import org.junit.Test;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import static com.mario.lynkband.vela.BandMessages.*;
import static org.junit.Assert.*;

public class LegacyInstallTest {
    @Test public void recognizesSerialServices() {
        UUID uuid = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb");
        assertEquals(uuid,VelaInstaller.serialService(uuid));
        assertNull(VelaInstaller.serialService(UUID.fromString("0000110b-0000-1000-8000-00805f9b34fb")));
    }

    private static final int VERSION = 36;
    private static final byte[] KEY = new byte[16];
    private static final byte[] RPK = new byte[451];

    @Test public void installsOverV1AndConfirmsExactVersion() throws Exception { exchange(VERSION, VERSION); }
    @Test public void mismatchedReadbackIsUnconfirmed() throws Exception { exchange(0, VERSION - 1); }
    @Test public void newerInstalledVersionStopsBeforePreflight() throws Exception { exchange(VERSION + 1,0); }

    @Test public void rejectedDeviceKeyDoesNotTriggerKeyPersistence() throws Exception {
        try (PipedInputStream incoming = new PipedInputStream(1024);
             DataOutputStream peer = new DataOutputStream(new PipedOutputStream(incoming));
             LegacyInstall session = new LegacyInstall(incoming, new ByteArrayOutputStream(), () -> false)) {
            frame(peer, 0, 0, new byte[] {1});
            byte[] challenge = join(bytes(1, new byte[16]), bytes(2, new byte[32]));
            frame(peer, 1, 2, cmd(1, 26, 3, bytes(31, challenge)));

            VelaInstaller.Progress progress = new VelaInstaller.Progress() {
                @Override public void report(String stage, int percent) { }
                @Override public void authenticated() { fail("A rejected key must not be saved"); }
            };
            IOException failure = assertThrows(IOException.class,
                    () -> session.install(KEY, 35, "test", RPK, VERSION, progress));
            assertEquals("V1 设备密钥不匹配。", failure.getMessage());
        }
    }

    private void exchange(int before, int after) throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (PipedInputStream phone = new PipedInputStream(8192); PipedOutputStream deviceOut = new PipedOutputStream(phone);
             PipedInputStream device = new PipedInputStream(8192); PipedOutputStream phoneOut = new PipedOutputStream(device);
             LegacyInstall session = new LegacyInstall(phone,phoneOut,() -> false)) {
            var simulation = pool.submit(() -> { simulate(new DataInputStream(device),new DataOutputStream(deviceOut),before,after); return true; });
            if (before > VERSION) {
                assertThrows(InstallFailure.class, () -> session.install(KEY,35,"test",RPK,VERSION,(stage,pct)->{}));
                assertEquals(0,device.available());
            } else {
                assertEquals(after == VERSION,session.install(KEY,35,"test",RPK,VERSION,(stage,pct)->{}));
            }
            assertTrue(simulation.get(3,TimeUnit.SECONDS));
        } finally { pool.shutdownNow(); }
    }

    private static void frame(DataOutputStream out,int channel,int type,byte[] payload) throws IOException {
        int len=payload.length+3;
        out.write(new byte[]{(byte)0xba,(byte)0xdc,(byte)0xfe,(byte)channel,(byte)0x80,(byte)len,(byte)(len>>8),2,0,(byte)type});
        out.write(payload); out.write(0xef); out.flush();
    }

    private static byte[] cmd(int module,int action,int field,byte[] data) {
        return join(number(1,module),number(2,action),bytes(field,data));
    }
    private static byte[] list(int code) {
        byte[] info=join(bytes(1,BandProtocol.APP_PACKAGE.getBytes(StandardCharsets.UTF_8)),number(3,code));
        return cmd(20,0,22,bytes(1,code == 0 ? new byte[0] : bytes(1,info)));
    }
    private static byte[] request(DataInputStream in, byte[] key, byte[] prefix,int seq) throws Exception {
        var f=LegacyInstall.read(in); assertEquals(2,f.channel()); assertEquals(1,f.encoding());
        ByteBuffer nonce=ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
        nonce.put(prefix).putInt(8,seq);
        byte[] data=f.data();
        assertEquals(seq,ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getShort());
        return BandProtocol.ccm(false,key,nonce.array(),Arrays.copyOfRange(data,2,data.length));
    }

    private static void simulate(DataInputStream in,DataOutputStream out,int before,int after) throws Exception {
        assertEquals(0,LegacyInstall.read(in).channel());
        frame(out,0,0,new byte[]{1});
        byte[] client=parse(LegacyInstall.read(in).data()).message(3).message(30).bytes(1);
        byte[] peer=new byte[16]; Arrays.fill(peer,(byte)17);
        byte[] material=BandProtocol.sessionMaterial(KEY,client,peer);
        byte[] rx=Arrays.copyOfRange(material,0,16),tx=Arrays.copyOfRange(material,16,32);
        byte[] rxNonce=new byte[12],txNonce=new byte[12];
        System.arraycopy(material,32,rxNonce,0,4); System.arraycopy(material,36,txNonce,0,4);
        frame(out,1,2,cmd(1,26,3,bytes(31,join(bytes(1,peer),bytes(2,BandProtocol.hmac(rx,join(peer,client)))))));
        var proof=parse(LegacyInstall.read(in).data()).message(3).message(32);
        assertArrayEquals(BandProtocol.hmac(tx,join(client,peer)),proof.bytes(1));
        assertEquals("test",new String(parse(BandProtocol.ccm(false,tx,txNonce,proof.bytes(2))).bytes(3),StandardCharsets.UTF_8));
        frame(out,1,2,cmd(1,27,3,bytes(33,number(1,1))));
        assertArrayEquals(requestInstalledApps(),request(in,tx,txNonce,1));
        frame(out,1,1,BandProtocol.ccm(true,rx,rxNonce,list(before)));
        if (before>VERSION) return;
        var preflight=parse(request(in,tx,txNonce,2)).message(22).message(2);
        assertEquals(VERSION,preflight.number(2)); assertEquals(RPK.length,preflight.number(3));
        frame(out,1,2,cmd(20,1,22,bytes(3,number(1,0))));
        byte[] digest=MessageDigest.getInstance("MD5").digest(RPK);
        assertArrayEquals(prepareTransfer(digest,RPK.length),request(in,tx,txNonce,3));
        frame(out,1,2,cmd(22,0,24,bytes(2,join(bytes(1,digest),number(2,0),number(5,128)))));
        ByteArrayOutputStream stream=new ByteArrayOutputStream();
        for (int i=1;i<=4;i++) {
            var f=LegacyInstall.read(in);assertEquals(5,f.channel());
            ByteBuffer b=ByteBuffer.wrap(BandProtocol.ccm(false,tx,txNonce,f.data())).order(ByteOrder.LITTLE_ENDIAN);
            assertEquals(4,b.getShort());assertEquals(i,b.getShort());stream.write(b.array(),4,b.remaining());
        }
        assertArrayEquals(BandProtocol.envelope(RPK,digest),stream.toByteArray());
        frame(out,1,2,cmd(20,2,22,bytes(4,number(1,0))));
        assertArrayEquals(requestInstalledApps(),request(in,tx,txNonce,4));
        frame(out,1,1,BandProtocol.ccm(true,rx,rxNonce,list(after)));
    }
}
