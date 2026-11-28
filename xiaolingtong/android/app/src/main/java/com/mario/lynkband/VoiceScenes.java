package com.mario.lynkband;

import java.util.LinkedHashMap;
import java.util.Map;

/** A second, fresh Mi Home scene confirms exactly one outstanding widget action. */
final class VoiceScenes {
    static final String PREFIX = "LYNK:";
    static final String ACTION = PREFIX + "动作:";
    static final String CONFIRM = PREFIX + "确认", CANCEL = PREFIX + "取消", TEST = PREFIX + "测试";
    static final String PACKAGE = "com.xiaomi.smarthome";
    record Choice(String slot, String signature, String label, String token) {}
    interface Card {
        Choice prepare(String name);
        String execute(Choice choice);
        void cancel();
    }

    private final Map<String, Long> recent = new LinkedHashMap<>();
    private final Map<String, Long> lastDirect = new LinkedHashMap<>();
    private Choice pending;
    private long preparedAt, expires;

    static boolean concrete(String label) {
        if (label == null || label.length() > 12 || label.contains(":")) return false;
        label = VoicePreset.canonical(label);
        return label.matches("(上锁|解锁|闭锁|落锁|寻车|鸣笛|闪灯|鸣笛闪灯|关窗|开窗|车窗通风|车窗微开|车窗全开|锁车|开锁|找车)")
                || label.matches("(开启|打开|关闭|停止|启动|熄灭|升起|降下|开|关)(车门锁|车窗|天窗|空调|引擎|发动机|后备箱|尾门|除霜|车锁)");
    }
    static String command(CharSequence... fields) {
        String found = "";
        for (CharSequence field : fields) {
            if (field == null) continue;
            String code = field.toString().trim();
            if (!code.startsWith(PREFIX)) continue;
            if (!(code.equals(TEST) || code.equals(CONFIRM) || code.equals(CANCEL)
                    || code.startsWith(ACTION) && concrete(code.substring(ACTION.length())))) return "";
            if (!found.isEmpty() && !found.equals(code)) return "";
            found = code;
        }
        return found;
    }

    void clear() { pending = null; }

    String receive(String code, String notificationKey, long posted, long wall, long elapsed, boolean confirmation, Card card) {
        if (code.isEmpty() || posted <= 0 || posted > wall || wall - posted > ActionTickets.TIMEOUT) return "";
        recent.entrySet().removeIf(entry -> wall - entry.getValue() > ActionTickets.TIMEOUT);
        String id = notificationKey + "/" + posted;
        if (recent.containsKey(id) || recent.size() >= 128) return "";
        recent.put(id, posted);
        if (code.equals(TEST)) return "已收到米家测试通知";
        if (code.equals(CANCEL)) {
            clear();
            card.cancel();
            return "已取消待确认的语音操作";
        }
        if (pending != null && elapsed >= expires) clear();
        if (code.equals(CONFIRM)) {
            if (pending == null) return "没有待确认的操作，请先说出具体动作";
            if (posted <= preparedAt) return "";
            Choice choice = pending;
            clear();
            return card.execute(choice);
        }
        if (!code.startsWith(ACTION)) return "";
        String name = VoicePreset.canonical(code.substring(ACTION.length()));
        if (!concrete(name)) return "";
        if (pending != null) {
            if (VoicePreset.canonical(pending.label).equals(name)) return "等待确认 · " + name;
            clear();
            return "动作已更换，请重新说一次要执行的动作";
        }
        pending = card.prepare(name);
        if (pending == null) return "当前卡片没有可用的「" + name + "」，请核对按钮标记和后台通信";
        if (!confirmation) {
            Choice choice = pending;
            clear();
            lastDirect.entrySet().removeIf(entry -> elapsed - entry.getValue() >= 5000);
            if (lastDirect.containsKey(name)) return "";
            lastDirect.put(name, elapsed);
            return card.execute(choice);
        }
        preparedAt = posted;
        expires = elapsed + ActionTickets.TIMEOUT - (wall - posted);
        return "待执行「" + name + "」，请在 15 秒内说“确认”";
    }
}
