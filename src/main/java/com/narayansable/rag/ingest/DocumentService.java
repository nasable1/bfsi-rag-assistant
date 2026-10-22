package com.narayansable.rag.ingest;

import com.narayansable.rag.config.RagProperties;
import com.narayansable.rag.guard.PiiRedactor;
import com.narayansable.rag.index.Retriever;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Owns the document lifecycle: stored text in the database, chunks in the search indexes.
 *
 * <p>At start-up the bundled corpus is written to the database (new files are added and changed
 * bundled files refreshed), then every stored document is chunked and indexed - all before the web
 * server accepts a request. Documents are PII-redacted before they are stored: chunk text is sent
 * to the model and returned as citation excerpts, so customer data in a document must not reach
 * either.
 */
public class DocumentService {

    public static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9-]{1,59}");
    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    public record Ingested(String id, String title, int chunks, List<String> piiMasked) {
    }

    private final RagProperties props;
    private final DocumentRepository repo;
    private final Retriever retriever;
    private final DocumentParser parser;
    private final PiiRedactor redactor;
    private final Chunker chunker;

    public DocumentService(RagProperties props, DocumentRepository repo, Retriever retriever, DocumentParser parser,
                           PiiRedactor redactor) {
        this.props = props;
        this.repo = repo;
        this.retriever = retriever;
        this.parser = parser;
        this.redactor = redactor;
        this.chunker = new Chunker(props.chunkSize(), props.chunkOverlap());
    }

    /** Seeds the bundled corpus and indexes everything stored. */
    public void start() {
        seedBundledCorpus();
        for (var d : repo.findAll()) {
            index(new ParsedDocument(d.id(), d.title(), d.content()));
        }
        log.info("Indexed {} documents, {} chunks (semantic search {})", retriever.documentCount(), retriever.size(),
                retriever.semanticEnabled() ? "on" : "off");
    }

    private void seedBundledCorpus() {
        try {
            for (Resource file : new PathMatchingResourcePatternResolver().getResources(props.corpus())) {
                String text;
                try (InputStream in = file.getInputStream()) {
                    text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
                String id = idFrom(file.getFilename());
                ParsedDocument doc = redacted(parser.markdown(id, text, id)).doc();
                String sha = sha256(doc.text());
                var existing = repo.find(id);
                if (existing.isPresent() && (!existing.get().source().equals("bundled")
                        || existing.get().contentSha().equals(sha))) {
                    continue;                     // unchanged, or replaced by an upload - leave it
                }
                repo.save(new DocumentRepository.StoredDocument(id, doc.title(), doc.text(), "bundled",
                        file.getFilename(), sha, "system", Instant.now()));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the bundled corpus " + props.corpus(), e);
        }
    }

    /** Stores and indexes an uploaded file, replacing any document with the same id. */
    public synchronized Ingested ingestFile(String id, String fileName, byte[] bytes, String user) {
        String docId = id == null || id.isBlank() ? idFrom(fileName) : id;
        requireValidId(docId);
        return store(parser.parse(docId, fileName, bytes), fileName, user);
    }

    /** Stores and indexes a Markdown document, replacing any document with the same id. */
    public synchronized Ingested ingestMarkdown(String id, String markdown, String user) {
        requireValidId(id);
        return store(parser.markdown(id, markdown, id), null, user);
    }

    private Ingested store(ParsedDocument parsed, String fileName, String user) {
        Redacted r = redacted(parsed);
        if (!r.masked().isEmpty()) {
            log.warn("Document {}: masked {} before storing", parsed.id(), r.masked());
        }
        ParsedDocument doc = r.doc();
        var chunks = chunker.split(doc);
        if (chunks.isEmpty()) {
            throw new DocumentParser.UnreadableDocumentException("The document contains no indexable text");
        }
        // Database first: if the write fails, the index still matches what is stored.
        repo.save(new DocumentRepository.StoredDocument(doc.id(), doc.title(), doc.text(), "upload",
                fileName == null ? null : clip(fileName.replaceAll("^.*[/\\\\]", ""), MAX_FILE_NAME),
                sha256(doc.text()), user, Instant.now()));
        retriever.replaceDocument(doc.id(), chunks);
        return new Ingested(doc.id(), doc.title(), chunks.size(), r.masked());
    }

    public synchronized boolean delete(String id) {
        boolean existed = repo.delete(id);
        retriever.removeDocument(id);
        return existed;
    }

    public List<DocumentRepository.Summary> list() {
        return repo.list();
    }

    private void index(ParsedDocument doc) {
        retriever.replaceDocument(doc.id(), chunker.split(doc));
    }

    private record Redacted(ParsedDocument doc, List<String> masked) {
    }

    private Redacted redacted(ParsedDocument d) {
        var text = redactor.redact(d.text());
        var title = redactor.redact(d.title());
        return new Redacted(new ParsedDocument(d.id(), clip(title.text().strip(), MAX_TITLE), text.text()), text.found());
    }

    /** The column widths of the documents table: a long first heading or file name must not fail the upload. */
    static final int MAX_TITLE = 300;
    static final int MAX_FILE_NAME = 255;

    static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 3).strip() + "...";
    }

    private static void requireValidId(String id) {
        if (id == null || !ID.matcher(id).matches()) {
            throw new DocumentParser.UnreadableDocumentException(
                    "Document id must be 2-60 characters: lower-case letters, digits and hyphens");
        }
    }

    /** A document id from a file name: "Locker Rent (2026).pdf" becomes "locker-rent-2026". */
    public static String idFrom(String filename) {
        String base = filename == null ? "" : filename.replaceAll("^.*[/\\\\]", "").replaceFirst("\\.[^.]+$", "");
        String id = base.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (id.length() > 60) {
            id = id.substring(0, 60).replaceAll("-+$", "");
        }
        return id.length() < 2 ? "document" : id;
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
