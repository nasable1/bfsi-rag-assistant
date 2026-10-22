package com.narayansable.rag.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.narayansable.rag.config.RagProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

/**
 * Tamper-evident record of every question and answer: each entry carries the SHA-256 of its own
 * content plus the previous entry's hash, so editing or deleting any past entry breaks the chain
 * from that point on ({@link #verify()}). Questions are stored after PII redaction - the audit log
 * must not become the place customer data leaks from.
 *
 * <p>With {@code rag.audit.file} set, entries are appended as JSON Lines. On start-up the existing
 * file is read back and the chain continues from its last entry, so one file holds one unbroken
 * chain across restarts, and an edit made to the file while the service was down shows up in
 * {@link #verify()}.
 */
@Component
public class AuditTrail {

    public record Entry(long seq, Instant at, String question, String answer, List<String> sources, String model,
                        List<String> flags, String prevHash, String hash) {
    }

    private static final Logger log = LoggerFactory.getLogger(AuditTrail.class);
    private static final String GENESIS = "0".repeat(64);
    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final List<Entry> entries = new ArrayList<>();
    private final Path file;

    public AuditTrail(RagProperties props) {
        String f = props.audit() == null ? null : props.audit().file();
        this.file = f == null || f.isBlank() ? null : Path.of(f);
        if (file != null && Files.exists(file)) {
            resume();
        }
    }

    private void resume() {
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    entries.add(JSON.readValue(line, Entry.class));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read audit file " + file, e);
        }
        long broken = verify(entries);
        if (broken < 0) {
            log.info("Audit trail resumed from {}: {} entries, chain intact", file, entries.size());
        } else {
            log.error("Audit trail {} is BROKEN at entry {} - it was edited or truncated", file, broken);
        }
    }

    public synchronized Entry record(String question, String answer, List<String> sources, String model,
                                     List<String> flags) {
        Entry last = entries.isEmpty() ? null : entries.get(entries.size() - 1);
        long seq = last == null ? 1 : last.seq() + 1;
        Instant at = Instant.now();
        String prev = last == null ? GENESIS : last.hash();
        String hash = sha256(canonical(seq, at, question, answer, sources, model, flags, prev));
        Entry e = new Entry(seq, at, question, answer, List.copyOf(sources), model, List.copyOf(flags), prev, hash);
        if (file != null) {
            // Persist first: an entry that is not on disk must not become part of the chain.
            try {
                Files.writeString(file, JSON.writeValueAsString(e) + "\n", StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        }
        entries.add(e);
        return e;
    }

    /** -1 when the chain is intact, else the sequence number of the first broken entry. */
    public synchronized long verify() {
        return verify(entries);
    }

    static long verify(List<Entry> chain) {
        String prev = GENESIS;
        long expectedSeq = 1;
        for (Entry e : chain) {
            String expected = sha256(canonical(e.seq(), e.at(), e.question(), e.answer(), e.sources(), e.model(),
                    e.flags(), prev));
            if (e.seq() != expectedSeq || !e.prevHash().equals(prev) || !e.hash().equals(expected)) {
                return e.seq();
            }
            prev = e.hash();
            expectedSeq++;
        }
        return -1;
    }

    public synchronized List<Entry> entries() {
        return List.copyOf(entries);
    }

    public synchronized int size() {
        return entries.size();
    }

    /**
     * The hashed form of an entry. It is the JSON array of the fields, so no value can be crafted to
     * look like a different split of fields or list items (as it could with a plain separator).
     */
    private static String canonical(long seq, Instant at, String q, String a, List<String> sources, String model,
                                    List<String> flags, String prev) {
        try {
            return JSON.writeValueAsString(Arrays.asList(seq, at.toString(), q, a, sources, model, flags, prev));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
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
