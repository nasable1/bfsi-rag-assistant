package com.narayansable.rag.ingest;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** The documents table: the extracted text of every indexed document, and who added it when. */
public class DocumentRepository {

    public record StoredDocument(String id, String title, String content, String source, String fileName,
                                 String contentSha, String uploadedBy, Instant uploadedAt) {
    }

    /** A document without its text, for listings. */
    public record Summary(String id, String title, String source, String fileName, String uploadedBy,
                          Instant uploadedAt) {
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public DocumentRepository(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    public void save(StoredDocument d) {
        tx.executeWithoutResult(s -> {
            jdbc.update("DELETE FROM documents WHERE id = ?", d.id());
            jdbc.update("INSERT INTO documents (id, title, content, source, file_name, content_sha, uploaded_by, "
                            + "uploaded_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)", d.id(), d.title(), d.content(), d.source(),
                    d.fileName(), d.contentSha(), d.uploadedBy(), Timestamp.from(d.uploadedAt()));
        });
    }

    public Optional<StoredDocument> find(String id) {
        return jdbc.query("SELECT * FROM documents WHERE id = ?", FULL, id).stream().findFirst();
    }

    public List<StoredDocument> findAll() {
        return jdbc.query("SELECT * FROM documents ORDER BY id", FULL);
    }

    public List<Summary> list() {
        return jdbc.query("SELECT id, title, source, file_name, uploaded_by, uploaded_at FROM documents ORDER BY title",
                (rs, i) -> new Summary(rs.getString("id"), rs.getString("title"), rs.getString("source"),
                        rs.getString("file_name"), rs.getString("uploaded_by"),
                        rs.getTimestamp("uploaded_at").toInstant()));
    }

    public boolean delete(String id) {
        return jdbc.update("DELETE FROM documents WHERE id = ?", id) > 0;
    }

    private static final RowMapper<StoredDocument> FULL = (rs, i) -> new StoredDocument(rs.getString("id"),
            rs.getString("title"), rs.getString("content"), rs.getString("source"), rs.getString("file_name"),
            rs.getString("content_sha").strip(), rs.getString("uploaded_by"),
            rs.getTimestamp("uploaded_at").toInstant());
}
