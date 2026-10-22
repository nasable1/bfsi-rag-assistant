package com.narayansable.rag.config;

import com.narayansable.rag.answer.AnswerService;
import com.narayansable.rag.audit.AuditTrail;
import com.narayansable.rag.guard.PiiRedactor;
import com.narayansable.rag.guard.PromptInjectionDetector;
import com.narayansable.rag.index.Embedder;
import com.narayansable.rag.index.GlossaryEmbedder;
import com.narayansable.rag.index.InMemoryVectorStore;
import com.narayansable.rag.index.LexicalIndex;
import com.narayansable.rag.index.MiniLmEmbedder;
import com.narayansable.rag.index.PgVectorStore;
import com.narayansable.rag.index.Retriever;
import com.narayansable.rag.index.VectorStore;
import com.narayansable.rag.ingest.DocumentParser;
import com.narayansable.rag.ingest.DocumentRepository;
import com.narayansable.rag.ingest.DocumentService;
import com.narayansable.rag.llm.LlmClient;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Locale;

/** Wires retrieval, storage, audit and the answer pipeline. */
@Configuration
public class RagConfig {

    private static final Logger log = LoggerFactory.getLogger(RagConfig.class);

    @Bean
    LexicalIndex lexicalIndex() {
        return new LexicalIndex();
    }

    @Bean
    Embedder embedder() {
        return new GlossaryEmbedder(new MiniLmEmbedder());
    }

    @Bean
    VectorStore vectorStore(RagProperties props, Embedder embedder, JdbcTemplate jdbc, TransactionTemplate tx) {
        String store = props.retrieval().vectorStore() == null ? "memory"
                : props.retrieval().vectorStore().strip().toLowerCase(Locale.ROOT);
        return switch (store) {
            case "memory" -> new InMemoryVectorStore(embedder);
            case "pgvector" -> {
                String db = jdbc.execute((ConnectionCallback<String>) c -> c.getMetaData().getDatabaseProductName());
                if (db == null || !db.toLowerCase(Locale.ROOT).contains("postgres")) {
                    throw new IllegalStateException("rag.retrieval.vector-store=pgvector needs a PostgreSQL database "
                            + "(this one is " + db + "); use the postgres profile or vector-store=memory");
                }
                yield new PgVectorStore(jdbc, tx, embedder);
            }
            default -> throw new IllegalStateException("rag.retrieval.vector-store must be memory or pgvector, not '"
                    + props.retrieval().vectorStore() + "'");
        };
    }

    @Bean
    Retriever retriever(LexicalIndex lexical, VectorStore vectors, Embedder embedder, RagProperties props,
                        MeterRegistry metrics) {
        boolean semantic = props.retrieval().semanticEnabled();
        if (semantic) {
            try {
                embedder.embed("warm-up");      // loads the model now, not on the first question
            } catch (RuntimeException | LinkageError e) {
                // e.g. ONNX Runtime has no native library for this platform: keep serving, lexically.
                log.error("Semantic search unavailable, continuing with lexical search only: {}", e.toString());
                semantic = false;
            }
        }
        Retriever r = semantic ? new Retriever(lexical, vectors, embedder, props.retrieval()) : new Retriever(lexical);
        Gauge.builder("rag.index.chunks", r, Retriever::size).register(metrics);
        Gauge.builder("rag.index.documents", r, Retriever::documentCount).register(metrics);
        return r;
    }

    @Bean
    DocumentRepository documentRepository(JdbcTemplate jdbc, TransactionTemplate tx) {
        return new DocumentRepository(jdbc, tx);
    }

    /** Indexing runs while the context starts, so the web server never serves an empty index. */
    @Bean(initMethod = "start")
    DocumentService documentService(RagProperties props, DocumentRepository repo, Retriever retriever,
                                    DocumentParser parser, PiiRedactor redactor) {
        return new DocumentService(props, repo, retriever, parser, redactor);
    }

    @Bean
    AuditTrail auditTrail(JdbcTemplate jdbc, TransactionTemplate tx) {
        return new AuditTrail(jdbc, tx);
    }

    @Bean
    AnswerService answerService(Retriever retriever, LlmClient llm, PiiRedactor redactor,
                                PromptInjectionDetector injection, AuditTrail audit, RagProperties props,
                                MeterRegistry metrics) {
        return new AnswerService(retriever, llm, redactor, injection, audit, props, metrics);
    }

    /**
     * Streaming answers run here, off the request thread. Bounded on purpose: when it is full, new
     * streams are refused with 503 rather than queueing without limit.
     */
    @Bean
    ThreadPoolTaskExecutor askExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(8);
        ex.setMaxPoolSize(16);
        ex.setQueueCapacity(32);
        ex.setThreadNamePrefix("ask-");
        ex.initialize();
        return ex;
    }
}
