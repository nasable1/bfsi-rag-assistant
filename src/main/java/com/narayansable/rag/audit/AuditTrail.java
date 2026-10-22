package com.narayansable.rag.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Tamper-evident record of every question and answer, in the database. Each entry carries the
 * SHA-256 of its own content plus the previous entry's hash, so editing or deleting any past entry
 * breaks the chain from that point on ({@link #verify()}). Questions are stored after PII
 * redaction - the audit log must not become the place customer data leaks from.
 *
 * <p>Only the head of the chain is held in memory; verification streams the table. Removing the
 * newest entries leaves a shorter but internally valid chain, so two more checks cover the tail:
 * this process remembers the head it last wrote, and an external system can record checkpoints
 * ({@link #head()}) and later confirm them ({@link #verifyCheckpoint}). On Postgres the table is
 * also append-only by trigger.
 */
public class AuditTrail {

    public record Entry(long seq, Instant at, String user, String question, String interpretedAs, String answer,
                        List<String> sources, String model, List<String> flags, String prevHash, String hash) {
    }

    /** {@code brokenAt} is -1 when the chain is intact, else the first entry that fails. */
    public record Verification(long entries, long brokenAt, String reason) {
        public boolean intact() {
            return brokenAt < 0;
        }
    }

    public record Head(long seq, String hash) {
    }

    static final String GENESIS = "0".repeat(64);
    private static final Logger log = LoggerFactory.getLogger(AuditTrail.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {
    };
    private static final String COLUMNS = "seq, at_utc, user_name, question, interpreted_as, answer, sources, model, "
            + "flags, prev_hash, hash";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate readOnly;
    private Head head;

    public AuditTrail(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.readOnly = new TransactionTemplate(tx.getTransactionManager());
        this.readOnly.setReadOnly(true);
        this.head = loadHead();
        Verification v = verify();
        if (v.intact()) {
            log.info("Audit trail: {} entries, chain intact", v.entries());
        } else {
            log.error("Audit trail BROKEN at entry {}: {}", v.brokenAt(), v.reason());
        }
    }

    public synchronized Entry record(String user, String question, String interpretedAs, String answer,
                                     List<String> sources, String model, List<String> flags) {
        for (int attempt = 1; ; attempt++) {
            long seq = head.seq() + 1;
            // Microseconds: what every database keeps, so the hashed text never differs from the stored one.
            Instant at = Instant.now().truncatedTo(ChronoUnit.MICROS);
            String hash = sha256(canonical(seq, at, user, question, interpretedAs, answer, sources, model, flags,
                    head.hash()));
            Entry e = new Entry(seq, at, user, question, interpretedAs, answer, List.copyOf(sources), model,
                    List.copyOf(flags), head.hash(), hash);
            try {
                jdbc.update("INSERT INTO audit_log (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        e.seq(), e.at().toString(), e.user(), e.question(), e.interpretedAs(), e.answer(),
                        json(e.sources()), e.model(), json(e.flags()), e.prevHash(), e.hash());
                head = new Head(seq, hash);
                return e;
            } catch (DuplicateKeyException ex) {
                // Another instance appended first: continue the chain from its entry.
                if (attempt == 3) {
                    throw ex;
                }
                head = loadHead();
            }
        }
    }

    /** Recomputes every hash, oldest first, and checks the chain reaches the head this process wrote. */
    public Verification verify() {
        Head expectedHead;
        synchronized (this) {
            expectedHead = head;
        }
        return readOnly.execute(status -> {
            long[] count = {0};
            long[] broken = {-1};
            String[] reason = {null};
            String[] prev = {GENESIS};
            jdbc.query(con -> {
                var ps = con.prepareStatement("SELECT " + COLUMNS + " FROM audit_log ORDER BY seq");
                ps.setFetchSize(500);
                return ps;
            }, rs -> {
                if (broken[0] >= 0) {
                    return;
                }
                long expectedSeq = count[0] + 1;
                Entry e;
                try {
                    e = MAPPER.mapRow(rs, 0);
                } catch (RuntimeException unreadable) {   // a hand-edited row may not even parse
                    broken[0] = rs.getLong("seq");
                    reason[0] = "entry " + broken[0] + " is unreadable";
                    return;
                }
                if (e.seq() != expectedSeq) {
                    broken[0] = expectedSeq;
                    reason[0] = "entry " + expectedSeq + " is missing";
                } else if (!e.prevHash().equals(prev[0]) || !e.hash().equals(hashOf(e, prev[0]))) {
                    broken[0] = e.seq();
                    reason[0] = "entry " + e.seq() + " was altered";
                }
                prev[0] = e.hash();
                count[0]++;
            });
            if (broken[0] < 0 && count[0] < expectedHead.seq()) {
                broken[0] = count[0] + 1;
                reason[0] = "entries after " + count[0] + " were deleted (this service wrote up to "
                        + expectedHead.seq() + ")";
            }
            return new Verification(count[0], broken[0], reason[0]);
        });
    }

    /** True when entry {@code seq} still has the hash an external checkpoint recorded, and the chain up to it is intact. */
    public boolean verifyCheckpoint(long seq, String hash) {
        Verification v = verify();
        if (!v.intact() && v.brokenAt() <= seq) {
            return false;
        }
        List<String> stored = jdbc.queryForList("SELECT hash FROM audit_log WHERE seq = ?", String.class, seq);
        return stored.size() == 1 && stored.get(0).equals(hash);
    }

    public synchronized Head head() {
        return head;
    }

    public synchronized long size() {
        return head.seq();
    }

    /** The newest entries, newest first. */
    public List<Entry> recent(int limit) {
        return jdbc.query("SELECT " + COLUMNS + " FROM audit_log ORDER BY seq DESC LIMIT ?", MAPPER, limit);
    }

    public Optional<Entry> entry(long seq) {
        return jdbc.query("SELECT " + COLUMNS + " FROM audit_log WHERE seq = ?", MAPPER, seq).stream().findFirst();
    }

    private Head loadHead() {
        return jdbc.query("SELECT seq, hash FROM audit_log ORDER BY seq DESC LIMIT 1",
                        (rs, i) -> new Head(rs.getLong(1), rs.getString(2).strip()))
                .stream().findFirst().orElse(new Head(0, GENESIS));
    }

    private static final RowMapper<Entry> MAPPER = (rs, i) -> new Entry(rs.getLong("seq"),
            Instant.parse(rs.getString("at_utc")), rs.getString("user_name"), rs.getString("question"),
            rs.getString("interpreted_as"), rs.getString("answer"), list(rs.getString("sources")),
            rs.getString("model"), list(rs.getString("flags")), rs.getString("prev_hash").strip(),
            rs.getString("hash").strip());

    static String hashOf(Entry e, String prev) {
        return sha256(canonical(e.seq(), e.at(), e.user(), e.question(), e.interpretedAs(), e.answer(), e.sources(),
                e.model(), e.flags(), prev));
    }

    /**
     * The hashed form of an entry: the JSON array of its fields, so no value can be crafted to look
     * like a different split of fields or list items.
     */
    private static String canonical(long seq, Instant at, String user, String q, String interpretedAs, String a,
                                    List<String> sources, String model, List<String> flags, String prev) {
        try {
            return JSON.writeValueAsString(Arrays.asList(seq, at.toString(), user, q, interpretedAs, a, sources, model,
                    flags, prev));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String json(List<String> values) {
        try {
            return JSON.writeValueAsString(values);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<String> list(String json) {
        try {
            return JSON.readValue(json, STRINGS);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unreadable audit column: " + json, e);
        }
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
