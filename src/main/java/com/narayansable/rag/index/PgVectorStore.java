package com.narayansable.rag.index;

import com.narayansable.rag.ingest.Chunk;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Chunk embeddings in Postgres (pgvector), searched with an HNSW cosine index. Each row keeps the
 * hash of the text it was computed from, so after a restart only new or changed chunks are
 * embedded again.
 */
public class PgVectorStore implements VectorStore {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Embedder embedder;

    public PgVectorStore(JdbcTemplate jdbc, TransactionTemplate tx, Embedder embedder) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.embedder = embedder;
    }

    @Override
    public void replaceDocument(String documentId, List<Chunk> chunks) {
        Map<String, String> stored = new HashMap<>();
        jdbc.query("SELECT chunk_id, text_hash FROM chunk_vectors WHERE document_id = ?",
                rs -> {
                    stored.put(rs.getString(1), rs.getString(2));
                }, documentId);
        List<Chunk> changed = new ArrayList<>();
        List<String> hashes = new ArrayList<>();
        Set<String> keep = new HashSet<>();
        for (Chunk c : chunks) {
            String h = sha256(InMemoryVectorStore.embeddingText(c));
            keep.add(c.id());
            if (!h.equals(stored.get(c.id()))) {
                changed.add(c);
                hashes.add(h);
            }
        }
        List<float[]> vectors = embedder.embedAll(changed.stream().map(InMemoryVectorStore::embeddingText).toList());
        tx.executeWithoutResult(status -> {
            for (String id : stored.keySet()) {
                if (!keep.contains(id)) {
                    jdbc.update("DELETE FROM chunk_vectors WHERE chunk_id = ?", id);
                }
            }
            for (int i = 0; i < changed.size(); i++) {
                Chunk c = changed.get(i);
                jdbc.update("""
                        INSERT INTO chunk_vectors (chunk_id, document_id, text_hash, embedding)
                        VALUES (?, ?, ?, CAST(? AS vector))
                        ON CONFLICT (chunk_id) DO UPDATE
                        SET document_id = EXCLUDED.document_id, text_hash = EXCLUDED.text_hash,
                            embedding = EXCLUDED.embedding""",
                        c.id(), documentId, hashes.get(i), literal(vectors.get(i)));
            }
        });
    }

    @Override
    public void removeDocument(String documentId) {
        jdbc.update("DELETE FROM chunk_vectors WHERE document_id = ?", documentId);
    }

    @Override
    public List<Hit> search(float[] query, int n) {
        String q = literal(query);
        return jdbc.query("""
                        SELECT chunk_id, 1 - (embedding <=> CAST(? AS vector)) AS cosine
                        FROM chunk_vectors ORDER BY embedding <=> CAST(? AS vector) LIMIT ?""",
                (rs, i) -> new Hit(rs.getString(1), rs.getDouble(2)), q, q, n);
    }

    /** pgvector's text form: {@code [0.1,0.2,...]}. */
    static String literal(float[] v) {
        StringBuilder b = new StringBuilder(v.length * 10).append('[');
        for (int i = 0; i < v.length; i++) {
            if (i > 0) {
                b.append(',');
            }
            b.append(v[i]);
        }
        return b.append(']').toString();
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
