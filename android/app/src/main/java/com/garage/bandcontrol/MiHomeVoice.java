package com.garage.bandcontrol;

import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

final class MiHomeVoice {
    private static final String PACKAGE = "com.xiaomi.smarthome";
    private static final String[] FIELDS = {"正文", "展开正文", "标题"};
    static final long MAX_AGE_MS = 30_000;
    static final long CONTROL_REPEAT_MS = 5_000;
    private static final int RECENT_LIMIT = 64;

    enum Command {
        TEST(null, "测试", null),
        LOCK_TOGGLE(CarAction.LYNK_UNLOCK, "切换车锁", null),
        WINDOW_TOGGLE(CarAction.LYNK_WINDOW, "切换车窗", null),
        TRUNK_TOGGLE(CarAction.TRUNK, "切换后备箱", null),
        LOCK(CarAction.LOCK, "上锁", ControlDirection.LOCK),
        UNLOCK(CarAction.LOCK, "解锁", ControlDirection.UNLOCK),
        CLIMATE(CarAction.CLIMATE),
        WINDOW_VENT(CarAction.WINDOW_VENT, CarAction.WINDOW_VENT.label, ControlDirection.OPEN),
        TRUNK(CarAction.TRUNK),
        FIND(CarAction.FIND),
        WINDOW_OPEN(CarAction.WINDOW_OPEN, CarAction.WINDOW_OPEN.label, ControlDirection.OPEN),
        HEAT(CarAction.HEAT),
        COOL(CarAction.COOL),
        PURIFY(CarAction.PURIFY),
        DEFROST(CarAction.DEFROST),
        SENTRY(CarAction.SENTRY),
        WINDOW_CLOSE(CarAction.WINDOW_OPEN, "关窗", ControlDirection.CLOSE),
        ENGINE(null, "发动机（待验证）", null),
        SUNROOF(null, "天窗（待验证）", null);

        final String code;
        final CarAction action;
        final String label;
        private final ControlDirection requiredDirection;

        Command(CarAction action) {
            this(action, action.label, null);
        }

        Command(CarAction action, String label, ControlDirection requiredDirection) {
            this.code = "GARAGE_V1:" + name();
            this.action = action;
            this.label = label;
            this.requiredDirection = requiredDirection;
        }

        String directionBlock(ControlDirection direction) {
            return requiredDirection == null || requiredDirection == direction
                    ? "" : "当前卡片没有「" + label + "」方向的按钮";
        }

        static Command fromText(CharSequence text) {
            for (Command command : values()) {
                if (text != null && command.code.contentEquals(text)) return command;
            }
            return null;
        }
    }

    static boolean enabled(String namespace, Command command) {
        if ("GEELY_V1".equals(namespace)) return switch (command) {
            case TEST, LOCK, UNLOCK, CLIMATE, FIND -> true;
            default -> false;
        };
        if ("LYNKCO_V1".equals(namespace)) return switch (command) {
            case TEST, LOCK_TOGGLE, WINDOW_TOGGLE, TRUNK_TOGGLE -> true;
            default -> false;
        };
        return "GARAGE_V1".equals(namespace) && command != Command.WINDOW_CLOSE
                && command != Command.WINDOW_TOGGLE && command != Command.LOCK_TOGGLE && command != Command.TRUNK_TOGGLE
                && command != Command.ENGINE && command != Command.SUNROOF;
    }

    static Command[] commands(String namespace) {
        return java.util.Arrays.stream(Command.values()).filter(command -> enabled(namespace, command)).toArray(Command[]::new);
    }

    static CarAction action(String namespace, Command command) {
        if (!enabled(namespace, command) || "LYNKCO_V1".equals(namespace)) return null; // Resolve Lynk slots on the live card.
        return command.action;
    }

    enum Admission { ACCEPTED, STALE, DUPLICATE }

    record Match(Command command, String field) {}
    private record Notice(String key, long postedAt) {}

    private final Set<Notice> notices = new LinkedHashSet<>();
    private final Map<Command, Long> nextControlAt = new EnumMap<>(Command.class);

    static boolean isSource(String packageName) { return PACKAGE.equals(packageName); }

    static Match match(CharSequence text, CharSequence bigText, CharSequence title) {
        return match("GARAGE_V1", text, bigText, title);
    }

    static Match match(String namespace, CharSequence text, CharSequence bigText, CharSequence title) {
        CharSequence[] values = {text, bigText, title};
        Match match = null;
        for (int index = 0; index < values.length; index++) {
            CharSequence value = values[index];
            Command command = null;
            for (Command candidate : Command.values()) {
                if (value != null && (namespace + ":" + candidate.name()).contentEquals(value)) command = candidate;
            }
            if (command != null && !enabled(namespace, command)) return null;
            if (command == null) continue;
            if (match == null) match = new Match(command, FIELDS[index]);
            else if (match.command() != command) return null;
        }
        return match;
    }

    Admission receive(String key, long postedAt, long now, long elapsed, Command command) {
        long age = now - postedAt;
        if (postedAt <= 0 || age < 0 || age > MAX_AGE_MS) return Admission.STALE;
        notices.removeIf(notice -> now - notice.postedAt() > MAX_AGE_MS);
        if (!notices.add(new Notice(key, postedAt))) return Admission.DUPLICATE;
        if (notices.size() > RECENT_LIMIT) notices.remove(notices.iterator().next());
        if (command.action != null) {
            if (elapsed < nextControlAt.getOrDefault(command, 0L)) return Admission.DUPLICATE;
            nextControlAt.put(command, elapsed + CONTROL_REPEAT_MS);
        }
        return Admission.ACCEPTED;
    }
}
