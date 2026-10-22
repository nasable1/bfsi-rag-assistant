package com.narayansable.rag.ingest;

import com.narayansable.rag.config.RagProperties;
import com.narayansable.rag.index.HybridIndex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Loads the Markdown corpus into the index at start-up; the first {@code # Heading} is the title. */
@Component
public class CorpusLoader {

    private static final Logger log = LoggerFactory.getLogger(CorpusLoader.class);

    private final RagProperties props;
    private final HybridIndex index;
    private final Chunker chunker;

    public CorpusLoader(RagProperties props, HybridIndex index) {
        this.props = props;
        this.index = index;
        this.chunker = new Chunker(props.chunkSize(), props.chunkOverlap());
    }

    @EventListener(ApplicationReadyEvent.class)
    public void load() throws IOException {
        Resource[] files = new PathMatchingResourcePatternResolver().getResources(props.corpus());
        for (Resource file : files) {
            String text = new String(file.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            ingest(idFrom(file.getFilename()), text);
        }
        log.info("Corpus loaded: {} documents, {} chunks", index.documentCount(), index.size());
    }

    /** Index one document; returns how many chunks it produced. */
    public int ingest(String id, String markdown) {
        String title = markdown.lines().filter(l -> l.startsWith("# ")).findFirst()
                .map(l -> l.substring(2).strip()).orElse(id);
        var chunks = chunker.split(new Document(id, title, markdown));
        index.addAll(chunks);
        return chunks.size();
    }

    static String idFrom(String filename) {
        String base = filename == null ? "document" : filename.replaceFirst("\\.[^.]+$", "");
        return base.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
    }
}
