package com.narayansable.rag.security;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A per-caller token bucket: {@code perMinute} requests a minute, refilled continuously. Every
 * question may cost a model call, so one leaked or misused key cannot run up the bill unchecked.
 * Per instance - behind several instances, put a shared limit at the gateway too.
 */
public class RateLimiter {

    private final double perMinute;
    private final Clock clock;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    private static final class Bucket {
        private double tokens;
        private long lastNanos;

        Bucket(double tokens, long now) {
            this.tokens = tokens;
            this.lastNanos = now;
        }
    }

    public RateLimiter(int perMinute) {
        this(perMinute, Clock.systemUTC());
    }

    RateLimiter(int perMinute, Clock clock) {
        this.perMinute = perMinute;
        this.clock = clock;
    }

    /** True when {@code caller} may make one more request now. */
    public boolean tryAcquire(String caller) {
        long now = nanos();
        Bucket b = buckets.computeIfAbsent(caller, k -> new Bucket(perMinute, now));
        synchronized (b) {
            b.tokens = Math.min(perMinute, b.tokens + (now - b.lastNanos) * perMinute / 60e9);
            b.lastNanos = now;
            if (b.tokens >= 1) {
                b.tokens -= 1;
                return true;
            }
            return false;
        }
    }

    private long nanos() {
        var i = clock.instant();
        return i.getEpochSecond() * 1_000_000_000L + i.getNano();
    }
}
