package com.otrong.carcontrol.phone;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

final class Confirmation {
    static final long LIFETIME_MS = 15_000;
    private final Map<String, Ticket> pending = new HashMap<>();

    private record Ticket(String token, CarAction action, String target, long expiresAt) {}

    String prepare(String node, CarAction action, String target, long now) {
        pending.values().removeIf(ticket -> now >= ticket.expiresAt);
        String token = UUID.randomUUID().toString();
        pending.put(node, new Ticket(token, action, target, now + LIFETIME_MS));
        return token;
    }

    boolean consume(String node, CarAction action, String token, String target, long now) {
        Ticket ticket = pending.get(node);
        if (ticket == null || !ticket.token.equals(token)) return false;
        pending.remove(node);
        return now < ticket.expiresAt && ticket.action == action && ticket.target.equals(target);
    }

    void cancel(String node, String token) {
        Ticket ticket = pending.get(node);
        if (ticket != null && ticket.token.equals(token)) pending.remove(node);
    }

    void disconnect(String node) {
        pending.remove(node);
    }

    void clear() {
        pending.clear();
    }
}
