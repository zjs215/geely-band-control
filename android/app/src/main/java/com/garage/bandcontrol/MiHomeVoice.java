package com.garage.bandcontrol;

import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

final class MiHomeVoice {
    private static final String CODE_PREFIX = "GARAGE_V1:";
    private static final String[] FIELDS = {"正文", "展开正文", "标题"};
    static final long MAX_AGE_MS = 30_000;
    static final long CONTROL_REPEAT_MS = 5_000;
    private static final int RECENT_LIMIT = 64;

    enum Source {
        MI_HOME("com.xiaomi.smarthome", "米家"),
        COLOROS_SHORTCUTS("com.coloros.shortcuts", "小布指令");

        final String packageName;
        final String label;

        Source(String packageName, String label) {
            this.packageName = packageName;
            this.label = label;
        }
    }

    enum Command {
        TEST(null, "测试", null),
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
        SENTRY(CarAction.SENTRY);

        final String code;
        final CarAction action;
        final String label;
        private final ControlDirection requiredDirection;

        Command(CarAction action) {
            this(action, action.label, null);
        }

        Command(CarAction action, String label, ControlDirection requiredDirection) {
            this.code = CODE_PREFIX + name();
            this.action = action;
            this.label = label;
            this.requiredDirection = requiredDirection;
        }

        String directionBlock(ControlDirection direction) {
            return requiredDirection == null || requiredDirection == direction
                    ? "" : "当前卡片没有「" + label + "」方向的按钮";
        }

        static Command fromText(CharSequence text) {
            if (text == null) return null;
            String normalized = text.toString().trim();
            for (Command command : values()) {
                if (command.code.equals(normalized)) return command;
            }
            return null;
        }
    }

    enum Admission { ACCEPTED, STALE, DUPLICATE }

    record Match(Command command, String field) {}
    private record Notice(String key, long postedAt) {}

    private final Set<Notice> notices = new LinkedHashSet<>();
    private final Map<Command, Long> nextControlAt = new EnumMap<>(Command.class);

    static Source sourceOf(String packageName) {
        if (packageName == null) return null;
        for (Source source : Source.values()) {
            if (source.packageName.equals(packageName)) return source;
        }
        return null;
    }

    static boolean isSource(String packageName) { return sourceOf(packageName) != null; }

    static Match match(CharSequence text, CharSequence bigText, CharSequence title) {
        CharSequence[] values = {text, bigText, title};
        Match match = null;
        for (int index = 0; index < values.length; index++) {
            Command command = Command.fromText(values[index]);
            if (command == null) continue;
            if (match == null) match = new Match(command, FIELDS[index]);
            else if (match.command() != command) return null;
        }
        return match;
    }

    static String unmatchedHint(CharSequence text, CharSequence bigText, CharSequence title) {
        CharSequence[] values = {text, bigText, title};
        StringBuilder fields = new StringBuilder();
        for (int index = 0; index < values.length; index++) {
            CharSequence value = values[index];
            if (value != null && value.toString().contains(CODE_PREFIX)) {
                if (fields.length() > 0) fields.append('、');
                fields.append(FIELDS[index]);
            }
        }
        if (fields.length() == 0) return "";
        return fields + "含指令前缀但未精确匹配，请确保内容仅填指令码且无冲突";
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
