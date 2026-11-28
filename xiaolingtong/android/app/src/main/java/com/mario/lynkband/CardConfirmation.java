package com.mario.lynkband;

// One continuation of a consumed execute ticket. Never persisted or renewed by polling.
final class CardConfirmation {
    static final long TIMEOUT = 6_000;
    record Action(String node, String card, String vehicle, String bindingKey, String signature, String label, long expires) {}
    private Action pending;

    void start(String node, String card, String vehicle, String bindingKey, String signature, String label, long now) {
        pending = new Action(node, card, vehicle, bindingKey, signature, label, now + TIMEOUT);
    }

    boolean waiting(long now) {
        if (pending != null && now >= pending.expires()) clear();
        return pending != null;
    }

    Action current(String card, String visibleVehicle, long now) {
        if (!waiting(now)) return null;
        if (!pending.card().equals(card)
                || (!visibleVehicle.isEmpty() && !pending.vehicle().equals(visibleVehicle))) clear();
        return pending;
    }

    void forget(String node) {
        if (pending != null && pending.node().equals(node)) clear();
    }

    void clear() { pending = null; }
}
