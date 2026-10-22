package com.narayansable.rag.security;

import com.narayansable.rag.config.RagProperties;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecurityUnitTest {

    private static RagProperties.Security keys(RagProperties.Security.ApiKey... k) {
        return new RagProperties.Security(List.of(k), 30);
    }

    @Test
    void keysMapToTheirCallerAndRole() {
        var r = new ApiKeyRegistry(keys(new RagProperties.Security.ApiKey("ops", "ops-key-0123456789abc", "admin"),
                new RagProperties.Security.ApiKey("teller", "teller-key-0123456789", "USER")));
        assertThat(r.lookup("ops-key-0123456789abc")).contains(new ApiKeyRegistry.Caller("ops", ApiKeyRegistry.Role.ADMIN));
        assertThat(r.lookup(" teller-key-0123456789 ")).get().extracting(ApiKeyRegistry.Caller::role)
                .isEqualTo(ApiKeyRegistry.Role.USER);
        assertThat(r.lookup("teller-key-012345678")).isEmpty();
        assertThat(r.lookup("")).isEmpty();
        assertThat(r.lookup(null)).isEmpty();
    }

    @Test
    void weakOrBrokenConfigurationFailsAtStartUp() {
        assertThatThrownBy(() -> new ApiKeyRegistry(keys(new RagProperties.Security.ApiKey("a", "short", "USER"))))
                .hasMessageContaining("shorter than 16");
        assertThatThrownBy(() -> new ApiKeyRegistry(keys(new RagProperties.Security.ApiKey("a", "0123456789abcdefg", "ROOT"))))
                .hasMessageContaining("USER or ADMIN");
        assertThatThrownBy(() -> new ApiKeyRegistry(keys(new RagProperties.Security.ApiKey("a", "0123456789abcdefg", "USER"),
                new RagProperties.Security.ApiKey("a", "0123456789abcdefgh", "USER")))).hasMessageContaining("unique name");
        assertThatThrownBy(() -> new ApiKeyRegistry(keys())).hasMessageContaining("No API keys");
    }

    @Test
    void anUnsetKeyIsGeneratedNotLeftOpen() {
        var r = new ApiKeyRegistry(keys(new RagProperties.Security.ApiKey("admin", "", "ADMIN")));
        assertThat(r.lookup("")).isEmpty();
        assertThat(r.lookup("anything-at-all-123")).isEmpty();
    }

    @Test
    void rateLimiterAllowsABurstThenRefills() {
        var clock = new MutableClock();
        var limiter = new RateLimiter(3, clock);
        assertThat(limiter.tryAcquire("u")).isTrue();
        assertThat(limiter.tryAcquire("u")).isTrue();
        assertThat(limiter.tryAcquire("u")).isTrue();
        assertThat(limiter.tryAcquire("u")).isFalse();
        assertThat(limiter.tryAcquire("other")).isTrue();      // callers are limited separately
        clock.advance(Duration.ofSeconds(20));                 // 3 per minute = one every 20 s
        assertThat(limiter.tryAcquire("u")).isTrue();
        assertThat(limiter.tryAcquire("u")).isFalse();
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
