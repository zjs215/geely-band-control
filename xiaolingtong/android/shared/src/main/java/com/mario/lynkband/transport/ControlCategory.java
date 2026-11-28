package com.mario.lynkband.transport;

/** Display grouping only. Control requests always identify an actual, labeled widget target. */
public enum ControlCategory {
    LOCK("车锁", "lock", "锁"),
    FIND("寻车", "find", "寻车", "找车", "鸣笛", "闪灯"),
    ENGINE("引擎", "engine", "引擎", "发动机", "点火", "熄火"),
    TRUNK("后备箱", "trunk", "后备箱", "后备厢", "尾门", "后舱", "行李箱", "后尾箱"),
    SUNROOF("天窗", "sunroof", "天窗", "天幕"),
    CLIMATE("空调", "climate", "空调", "制冷", "制热"),
    WINDOW("车窗", "window", "车窗", "开窗", "关窗", "升窗", "降窗", "窗户"),
    OTHER("其他", "more");

    public final String title;
    public final String symbol;
    private final String[] keywords;

    ControlCategory(String title, String symbol, String... keywords) {
        this.title = title;
        this.symbol = symbol;
        this.keywords = keywords;
    }

    public static ControlCategory resolve(String assigned, String label) {
        for (ControlCategory category : values()) {
            if (category.name().equals(assigned)) return category;
        }
        ControlCategory match = OTHER;
        for (ControlCategory category : values()) {
            for (String keyword : category.keywords) {
                if (label.contains(keyword)) {
                    if (match != OTHER) return OTHER;
                    match = category;
                    break;
                }
            }
        }
        return match;
    }
}
