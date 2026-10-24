package com.narayansable.rag.security;

import com.narayansable.rag.config.RagProperties;
import com.narayansable.rag.index.Access;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The configured API keys, held only as SHA-256 digests and compared in constant time.
 *
 * <p>A configured entry with no key (the environment variable is unset) gets a random key for this
 * run, logged once - convenient on a laptop, and never silent. Production sets every key.
 */
public class ApiKeyRegistry {

    public enum Role { USER, ADMIN }

    public record Caller(String name, Role role, Set<String> groups) {

        public Caller {
            groups = Set.copyOf(groups == null ? Set.of() : groups);
        }

        public Caller(String name, Role role) {
            this(name, role, Set.of());
        }

        /** ADMIN retrieves everything; a USER sees open documents and those of its groups. */
        public Access access() {
            return role == Role.ADMIN ? Access.ALL : new Access(false, groups);
        }
    }

    private record Stored(byte[] digest, Caller caller) {
    }

    static final int MIN_KEY_LENGTH = 16;
    private static final Logger log = LoggerFactory.getLogger(ApiKeyRegistry.class);
    private final List<Stored> keys = new ArrayList<>();

    public ApiKeyRegistry(RagProperties.Security cfg) {
        List<RagProperties.Security.ApiKey> configured = cfg == null || cfg.apiKeys() == null ? List.of() : cfg.apiKeys();
        Set<String> names = new HashSet<>();
        for (var k : configured) {
            if (k.name() == null || k.name().isBlank() || !names.add(k.name())) {
                throw new IllegalStateException("Every rag.security.api-keys entry needs a unique name");
            }
            Role role;
            try {
                role = Role.valueOf(k.role() == null ? "" : k.role().strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("API key '" + k.name() + "': role must be USER or ADMIN");
            }
            String key = k.key();
            if (key == null || key.isBlank()) {
                key = generate();
                log.warn("No key configured for API caller '{}' ({}); generated one for this run: {}", k.name(), role, key);
            } else if (key.strip().length() < MIN_KEY_LENGTH) {
                throw new IllegalStateException("API key for '" + k.name() + "' is shorter than " + MIN_KEY_LENGTH
                        + " characters");
            }
            byte[] digest = sha256(key.strip());
            // One key for two callers would resolve to whichever is listed last - possibly ADMIN for a USER.
            if (keys.stream().anyMatch(s -> MessageDigest.isEqual(s.digest(), digest))) {
                throw new IllegalStateException("API key for '" + k.name() + "' is the same as another caller's");
            }
            Set<String> groups;
            try {
                groups = Access.normalise(k.groups());
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("API key '" + k.name() + "': " + e.getMessage());
            }
            keys.add(new Stored(digest, new Caller(k.name(), role, groups)));
        }
        if (keys.isEmpty()) {
            throw new IllegalStateException("No API keys configured (rag.security.api-keys)");
        }
    }

    /** The caller a presented key belongs to; every stored key is compared, so timing reveals nothing. */
    public Optional<Caller> lookup(String presented) {
        if (presented == null || presented.isBlank()) {
            return Optional.empty();
        }
        byte[] digest = sha256(presented.strip());
        Caller match = null;
        for (Stored s : keys) {
            if (MessageDigest.isEqual(s.digest(), digest)) {
                match = s.caller();
            }
        }
        return Optional.ofNullable(match);
    }

    private static String generate() {
        byte[] b = new byte[24];
        new SecureRandom().nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
