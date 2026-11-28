// Adapted for 小领通 from zjs215/geely-band-control (Apache-2.0).
package com.mario.lynkband.vela;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Only the nonce, install and transfer messages used by the Band 10 Pro installer. */
final class BandMessages {
    static final int APP = 20;
    static final int MASS = 22;
    static final int APP_DATA = 64;
    static final int PREPARE_APP = 1;
    private static final int INSTALLED_APPS = 0;
    static final int INSTALL_RESULT = 2;
    static final int PREPARE_MASS = 0;
    static final int AUTH = 1;
    static final int AUTH_VERIFY = 26;
    static final int AUTH_CONFIRM = 27;
    private static final int LEGACY_VERIFY = 5;
    private static final int SYSTEM = 2;
    private static final int GET_DEVICE_INFO = 2;
    private static final int APP_PAYLOAD = 22;
    private static final int MASS_PAYLOAD = 24;
    // Band 10 Pro uses this compatibility marker in PREPARE_APP. The real
    // application version is taken from the signed, unmodified RPK.
    private static final int INSTALL_VERSION_MARKER = 114514;
    private static final byte[] EMPTY = new byte[0];

    record Response(int type, int action, int status, int sliceLength) {}
    record Challenge(byte[] nonce, byte[] hmac) {}
    enum AuthStatus { APPROVED, REJECTED, ERROR, UNSPECIFIED }
    record AuthReply(AuthStatus status, int errorCode) {}
    record InstalledApp(boolean present, int versionCode) {}

    private BandMessages() {}

    static byte[] phoneNonce(byte[] nonce) {
        return command(AUTH_VERIFY, bytes(30, bytes(1, nonce)));
    }

    static byte[] authProof(byte[] hmac, byte[] deviceInfo) {
        return command(AUTH_CONFIRM, bytes(32, join(bytes(1, hmac), bytes(2, deviceInfo))));
    }

    private static byte[] command(int action, byte[] auth) {
        return join(number(1, AUTH), number(2, action), bytes(3, auth));
    }

    static byte[] deviceInfo(int api, String model, String region) {
        byte[] apiField = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN)
                .put((byte) ((2 << 3) | 5)).putFloat(api).array();
        return join(number(1, 0), apiField, bytes(3, utf8(model)), number(4, 224),
                bytes(5, utf8(region)));
    }

    static Challenge challenge(byte[] body) throws IOException {
        Fields command = parse(body);
        if (command.number(1) != AUTH || command.number(2) != AUTH_VERIFY) return null;
        Fields challenge = command.message(3).message(31);
        byte[] nonce = challenge.bytes(1);
        byte[] hmac = challenge.bytes(2);
        return nonce.length == 16 && hmac.length == 32 ? new Challenge(nonce, hmac) : null;
    }

    static int authError(byte[] body, int expectedAction) throws IOException {
        Fields command = parse(body);
        return command.number(1) == AUTH && command.number(2) == expectedAction
                ? command.message(3).number(3) : -1;
    }

    static AuthReply authConfirmation(byte[] body) throws IOException {
        Fields command = parse(body);
        int action = command.number(2);
        if (command.number(1) != AUTH || (action != AUTH_CONFIRM && action != LEGACY_VERIFY)
                || !command.values().containsKey(3)) return null;
        Fields account = command.message(3);
        int error = account.number(3);
        if (error > 0) return new AuthReply(AuthStatus.ERROR, error);
        int result = action == AUTH_CONFIRM ? account.message(33).number(1) : account.number(8);
        return new AuthReply(switch (result) {
            case 1 -> AuthStatus.APPROVED;
            case 0 -> AuthStatus.REJECTED;
            default -> AuthStatus.UNSPECIFIED;
        }, error);
    }

    static byte[] requestDeviceInfo() {
        return join(number(1, SYSTEM), number(2, GET_DEVICE_INFO));
    }

    static boolean isDeviceInfoReply(byte[] body) throws IOException {
        Fields command = parse(body);
        if (command.number(1) != SYSTEM || command.number(2) != GET_DEVICE_INFO) return false;
        Fields info = command.message(4).message(3);
        return info.values().containsKey(2) && info.values().containsKey(4);
    }

    static byte[] prepareApp(String packageName, int size) {
        return prepareApp(packageName, size, INSTALL_VERSION_MARKER);
    }

    static byte[] prepareApp(String packageName, int size, int versionCode) {
        byte[] request = join(bytes(1, utf8(packageName)), number(2, versionCode),
                number(3, size));
        return join(number(1, APP), number(2, PREPARE_APP), bytes(APP_PAYLOAD, bytes(2, request)));
    }

    static byte[] prepareTransfer(byte[] contentId, int size) {
        byte[] request = join(number(1, APP_DATA), bytes(2, contentId), number(3, size));
        return join(number(1, MASS), number(2, PREPARE_MASS), bytes(MASS_PAYLOAD, bytes(1, request)));
    }

    static byte[] requestInstalledApps() {
        return join(number(1, APP), number(2, INSTALLED_APPS));
    }

    static InstalledApp installedApp(byte[] body, String packageName) throws IOException {
        Fields packet = parse(body);
        if (packet.number(1) != APP || packet.number(2) != INSTALLED_APPS) return null;
        Fields appPacket = packet.message(APP_PAYLOAD);
        if (!appPacket.values().containsKey(1)) return null;
        for (byte[] item : appPacket.message(1).repeated(1)) {
            Fields app = parse(item);
            if (packageName.equals(new String(app.bytes(1), StandardCharsets.UTF_8))) {
                return new InstalledApp(true, app.number(3));
            }
        }
        return new InstalledApp(false, -1);
    }

    static Response response(byte[] body) throws IOException {
        Fields packet = parse(body);
        int type = packet.number(1);
        int action = packet.number(2);
        if (type == APP && action == PREPARE_APP) {
            Fields prepare = packet.message(APP_PAYLOAD).message(3);
            return new Response(type, action, prepare.number(1), prepare.number(2));
        }
        if (type == MASS && action == PREPARE_MASS) {
            Fields prepare = packet.message(MASS_PAYLOAD).message(2);
            return new Response(type, action, prepare.number(2), prepare.number(5));
        }
        if (type == APP && action == INSTALL_RESULT) {
            Fields result = packet.message(APP_PAYLOAD).message(4);
            return new Response(type, action, result.number(1), -1);
        }
        return null;
    }

    record Fields(Map<Integer, Integer> numbers, Map<Integer, List<byte[]>> values) {
        int number(int field) { return numbers.getOrDefault(field, -1); }
        List<byte[]> repeated(int field) { return values.getOrDefault(field, Collections.emptyList()); }
        byte[] bytes(int field) {
            List<byte[]> fieldValues = repeated(field);
            return fieldValues.isEmpty() ? EMPTY : fieldValues.get(fieldValues.size() - 1);
        }
        Fields message(int field) throws IOException { return parse(bytes(field)); }
    }

    static Fields parse(byte[] data) throws IOException {
        ByteBuffer input = ByteBuffer.wrap(data);
        Fields fields = new Fields(new HashMap<>(), new HashMap<>());
        while (input.hasRemaining()) {
            long tag = readVarint(input);
            int field = (int) (tag >>> 3);
            if (field == 0 || tag > 0xffffffffL || tag < 0) {
                throw new IOException("Invalid Vela message tag");
            }
            switch ((int) (tag & 7)) {
                case 0 -> {
                    long value = readVarint(input);
                    if (value >= 0 && value <= Integer.MAX_VALUE) fields.numbers().put(field, (int) value);
                }
                case 1 -> skip(input, 8);
                case 2 -> {
                    long size = readVarint(input);
                    if (size < 0 || size > input.remaining()) throw new IOException("Invalid Vela field size");
                    byte[] value = new byte[(int) size];
                    input.get(value);
                    fields.values().computeIfAbsent(field, ignored -> new ArrayList<>()).add(value);
                }
                case 5 -> skip(input, 4);
                default -> throw new IOException("Unsupported Vela message field");
            }
        }
        return fields;
    }

    private static void skip(ByteBuffer input, int size) throws IOException {
        if (size > input.remaining()) throw new IOException("Truncated Vela message");
        input.position(input.position() + size);
    }

    private static long readVarint(ByteBuffer input) throws IOException {
        long value = 0;
        for (int shift = 0; shift <= 63 && input.hasRemaining(); shift += 7) {
            int digit = input.get() & 0xff;
            if (shift == 63 && digit > 1) break;
            value |= (long) (digit & 0x7f) << shift;
            if ((digit & 0x80) == 0) return value;
        }
        throw new IOException("Invalid Vela varint");
    }

    static byte[] number(int field, int value) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeVarint(output, field << 3);
        writeVarint(output, value);
        return output.toByteArray();
    }

    static byte[] bytes(int field, byte[] value) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeVarint(output, (field << 3) | 2);
        writeVarint(output, value.length);
        output.write(value, 0, value.length);
        return output.toByteArray();
    }

    private static void writeVarint(ByteArrayOutputStream output, int value) {
        while (value > 0x7f) {
            output.write((value & 0x7f) | 0x80);
            value >>>= 7;
        }
        output.write(value);
    }

    static byte[] join(byte[]... chunks) {
        int length = 0;
        for (byte[] chunk : chunks) length += chunk.length;
        ByteBuffer output = ByteBuffer.allocate(length);
        for (byte[] chunk : chunks) output.put(chunk);
        return output.array();
    }

    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
