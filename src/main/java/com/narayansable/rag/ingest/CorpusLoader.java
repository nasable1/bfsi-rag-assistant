package com.narayansable.rag.ingest;

import com.narayansable.rag.config.RagProperties;
import com.narayansable.rag.guard.PiiRedactor;
import com.narayansable.rag.index.HybridIndex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Loads the Markdown corpus into the index at start-up; the first {@code # Heading} is the title.
 *
 * <p>Loading happens while the application context is built, so the web server never accepts a
 * question against an empty index. Documents are PII-redacted before indexing: chunk text is sent
 * to the model and returned as citation excerpts, so a document that contains customer data must
 * not carry it into either.
 */
@Component
public class CorpusLoader implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(CorpusLoader.class);

    private final RagProperties props;
    private final HybridIndex index;
    private final PiiRedactor redactor;
    private final Chunker chunker;

    public CorpusLoader(RagProperties props, HybridIndex index, PiiRedactor redactor) {
        this.props = props;
        this.index = index;
        this.redactor = redactor;
        this.chunker = new Chunker(props.chunkSize(), props.chunkOverlap());
    }

    @Override
    public void afterPropertiesSet() throws IOException {
        load();
    }

    public void load() throws IOException {
        Resource[] files = new PathMatchingResourcePatternResolver().getResources(props.corpus());
        for (Resource file : files) {
            try (InputStream in = file.getInputStream()) {
                ingest(idFrom(file.getFilename()), new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        log.info("Corpus loaded: {} documents, {} chunks", index.documentCount(), index.size());
    }

    /** Index one document, replacing any earlier version with the same id; returns its chunk count. */
    public int ingest(String id, String markdown) {
        var redacted = redactor.redact(markdown);
        if (redacted.redacted()) {
            log.warn("Document {}: masked {} before indexing", id, redacted.found());
        }
        String text = redacted.text();
        String title = text.lines().filter(l -> l.startsWith("# ")).findFirst()
                .map(l -> l.substring(2).strip()).orElse(id);
        var chunks = chunker.split(new Document(id, title, text));
        index.replaceDocument(id, chunks);
        return chunks.size();
    }

    static String idFrom(String filename) {
        String base = filename == null ? "document" : filename.replaceFirst("\\.[^.]+$", "");
        return base.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
    }
}
