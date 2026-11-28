package com.mario.lynkband;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

final class ActionTickets {
    static final long TIMEOUT = 15_000;
    private record Ticket(String token, String target, long until) {}
    private final Map<String, Ticket> pending = new HashMap<>();

    String issue(String node, String target, long now) {
        pending.values().removeIf(entry -> entry.until <= now);
        String token = UUID.randomUUID().toString();
        pending.put(node, new Ticket(token, target, now + TIMEOUT));
        return token;
    }

    boolean take(String node, String token, String target, long now) {
        Ticket ticket = pending.get(node);
        if (ticket == null || !ticket.token.equals(token)) return false;
        pending.remove(node);
        return now < ticket.until && ticket.target.equals(target);
    }

    void forget(String node) { pending.remove(node); }
    void clear() { pending.clear(); }
}
