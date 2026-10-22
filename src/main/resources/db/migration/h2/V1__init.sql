-- Documents are stored as extracted text; the search indexes are rebuilt from them at start-up.
CREATE TABLE documents (
    id           VARCHAR(60)  PRIMARY KEY,
    title        VARCHAR(300) NOT NULL,
    content      CLOB         NOT NULL,
    source       VARCHAR(20)  NOT NULL,     -- bundled | upload
    file_name    VARCHAR(255),
    content_sha  CHAR(64)     NOT NULL,
    uploaded_by  VARCHAR(100) NOT NULL,
    uploaded_at  TIMESTAMP WITH TIME ZONE NOT NULL
);

-- Hash-chained audit trail. at_utc is the exact Instant text that was hashed.
CREATE TABLE audit_log (
    seq             BIGINT       PRIMARY KEY,
    at_utc          VARCHAR(40)  NOT NULL,
    user_name       VARCHAR(100) NOT NULL,
    question        CLOB         NOT NULL,
    interpreted_as  CLOB,
    answer          CLOB         NOT NULL,
    sources         CLOB         NOT NULL,  -- JSON array
    model           VARCHAR(100) NOT NULL,
    flags           CLOB         NOT NULL,  -- JSON array
    prev_hash       CHAR(64)     NOT NULL,
    hash            CHAR(64)     NOT NULL
);
