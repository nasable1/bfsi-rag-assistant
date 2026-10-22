CREATE EXTENSION IF NOT EXISTS vector;

-- Documents are stored as extracted text; the lexical index is rebuilt from them at start-up.
CREATE TABLE documents (
    id           VARCHAR(60)  PRIMARY KEY,
    title        VARCHAR(300) NOT NULL,
    content      TEXT         NOT NULL,
    source       VARCHAR(20)  NOT NULL,     -- bundled | upload
    file_name    VARCHAR(255),
    content_sha  CHAR(64)     NOT NULL,
    uploaded_by  VARCHAR(100) NOT NULL,
    uploaded_at  TIMESTAMPTZ  NOT NULL
);

-- Chunk embeddings (all-MiniLM-L6-v2, 384 dimensions); text_hash avoids re-embedding unchanged chunks.
CREATE TABLE chunk_vectors (
    chunk_id     VARCHAR(80)  PRIMARY KEY,
    document_id  VARCHAR(60)  NOT NULL,
    text_hash    CHAR(64)     NOT NULL,
    embedding    vector(384)  NOT NULL
);
CREATE INDEX chunk_vectors_document ON chunk_vectors (document_id);
CREATE INDEX chunk_vectors_hnsw ON chunk_vectors USING hnsw (embedding vector_cosine_ops);

-- Hash-chained audit trail. at_utc is the exact Instant text that was hashed.
CREATE TABLE audit_log (
    seq             BIGINT       PRIMARY KEY,
    at_utc          VARCHAR(40)  NOT NULL,
    user_name       VARCHAR(100) NOT NULL,
    question        TEXT         NOT NULL,
    interpreted_as  TEXT,
    answer          TEXT         NOT NULL,
    sources         TEXT         NOT NULL,  -- JSON array
    model           VARCHAR(100) NOT NULL,
    flags           TEXT         NOT NULL,  -- JSON array
    prev_hash       CHAR(64)     NOT NULL,
    hash            CHAR(64)     NOT NULL
);

-- Append-only at the database level: the application role cannot rewrite or remove history,
-- not even by accident. (A superuser can still drop the triggers - pair this with checkpoints
-- of the chain head held outside the database; see GET /api/audit/head.)
CREATE FUNCTION audit_log_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'audit_log is append-only';
END;
$$;
CREATE TRIGGER audit_log_no_update_delete BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION audit_log_append_only();
CREATE TRIGGER audit_log_no_truncate BEFORE TRUNCATE ON audit_log
    FOR EACH STATEMENT EXECUTE FUNCTION audit_log_append_only();
