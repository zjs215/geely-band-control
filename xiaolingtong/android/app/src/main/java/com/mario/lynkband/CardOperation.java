package com.mario.lynkband;

/** Observes provider updates after dispatch, including operations without a confirm overlay. */
final class CardOperation {
    static final long TIMEOUT = 45_000;
    private String binding = "", item, goal;
    private long deadline;
    private boolean loading;

    void start(String key, String name, String initialState, long now) {
        clear();
        if (!"open".equals(initialState) && !"closed".equals(initialState)) return;
        binding = key;
        item = name;
        goal = "open".equals(initialState) ? "closed" : "open";
        deadline = now + TIMEOUT;
        loading = false;
    }

    boolean active() { return !binding.isEmpty(); }
    String bindingKey() { return binding; }
    void clear() { binding = ""; }

    String observe(String key, String state, long now) {
        if (!active()) return "";
        if (now >= deadline) {
            clear();
            return item + " · 结果待核对";
        }
        if (!binding.equals(key)) return "";
        if (state.equals(goal)) {
            clear();
            return item + " · 已完成";
        }
        if (state.equals("loading") && !loading) {
            loading = true;
            return item + (goal.equals("open") ? " · 开启中" : " · 关闭中");
        }
        if (loading && !state.equals("loading")) {
            loading = false;
            return item + " · 结果待核对";
        }
        return "";
    }
}
