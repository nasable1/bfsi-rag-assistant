package com.narayansable.rag.audit;

import com.narayansable.rag.config.RagProperties;
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
import java.util.HexFormat;
import java.util.List;

/**
 * Tamper-evident record of every question and answer: each entry carries the SHA-256 of its own
 * content plus the previous entry's hash, so editing or deleting any past entry breaks the chain
 * from that point on ({@link #verify()}). Questions are stored after PII redaction - the audit log
 * must not become the place customer data leaks from.
 */
@Component
public class AuditTrail {

    public record Entry(long seq, Instant at, String question, String answer, List<String> sources, String model,
                        List<String> flags, String prevHash, String hash) {
    }

    private static final String GENESIS = "0".repeat(64);
    private final List<Entry> entries = new ArrayList<>();
    private final Path file;

    public AuditTrail(RagProperties props) {
        String f = props.audit() == null ? null : props.audit().file();
        this.file = f == null || f.isBlank() ? null : Path.of(f);
    }

    public synchronized Entry record(String question, String answer, List<String> sources, String model,
                                     List<String> flags) {
        long seq = entries.size() + 1L;
        Instant at = Instant.now();
        String prev = entries.isEmpty() ? GENESIS : entries.get(entries.size() - 1).hash();
        String hash = sha256(canonical(seq, at, question, answer, sources, model, flags, prev));
        Entry e = new Entry(seq, at, question, answer, List.copyOf(sources), model, List.copyOf(flags), prev, hash);
        entries.add(e);
        if (file != null) {
            try {
                Files.writeString(file, e + System.lineSeparator(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        }
        return e;
    }

    /** -1 when the chain is intact, else the sequence number of the first broken entry. */
    public synchronized long verify() {
        return verify(entries);
    }

    static long verify(List<Entry> chain) {
        String prev = GENESIS;
        for (Entry e : chain) {
            String expected = sha256(canonical(e.seq(), e.at(), e.question(), e.answer(), e.sources(), e.model(),
                    e.flags(), prev));
            if (!e.prevHash().equals(prev) || !e.hash().equals(expected)) {
                return e.seq();
            }
            prev = e.hash();
        }
        return -1;
    }

    public synchronized List<Entry> entries() {
        return List.copyOf(entries);
    }

    private static String canonical(long seq, Instant at, String q, String a, List<String> sources, String model,
                                    List<String> flags, String prev) {
        return String.join("\u001f", Long.toString(seq), at.toString(), q, a, String.join(",", sources), model,
                String.join(",", flags), prev);
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
