package com.narayansable.rag;

import com.narayansable.rag.answer.AnswerService;
import com.narayansable.rag.audit.AuditTrail;
import com.narayansable.rag.config.RagProperties;
import com.narayansable.rag.guard.PiiRedactor;
import com.narayansable.rag.guard.PromptInjectionDetector;
import com.narayansable.rag.index.Embedder;
import com.narayansable.rag.index.GlossaryEmbedder;
import com.narayansable.rag.index.InMemoryVectorStore;
import com.narayansable.rag.index.LexicalIndex;
import com.narayansable.rag.index.MiniLmEmbedder;
import com.narayansable.rag.index.Retriever;
import com.narayansable.rag.ingest.DocumentParser;
import com.narayansable.rag.ingest.DocumentRepository;
import com.narayansable.rag.ingest.DocumentService;
import com.narayansable.rag.llm.LlmClient;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** The production pipeline over the bundled corpus, with no Spring context and a pluggable model. */
public final class TestSupport {

    public static final RagProperties PROPS = props(0.6);

    /** One model instance for the whole test run: loading it is the slow part. */
    public static final Embedder EMBEDDER = new GlossaryEmbedder(new MiniLmEmbedder());

    private TestSupport() {
    }

    public static RagProperties props(double semanticWeight) {
        return new RagProperties("classpath*:corpus/*.md", 700, 120, 4, 0.30,
                new RagProperties.Llm("extractive", "test", "medium", 700, 10),
                new RagProperties.Retrieval(semanticWeight, 0.25, "memory"),
                new RagProperties.Conversation(3, 1000),
                new RagProperties.Security(List.of(), 1000));
    }

    /** A fresh in-memory database with the production schema. */
    public static Db database() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(ds).locations("classpath:db/migration/h2").load().migrate();
        return new Db(new JdbcTemplate(ds), new TransactionTemplate(new DataSourceTransactionManager(ds)));
    }

    public record Db(JdbcTemplate jdbc, TransactionTemplate tx) {
        public AuditTrail audit() {
            return new AuditTrail(jdbc, tx);
        }
    }

    public static Retriever retriever(RagProperties props) {
        return new Retriever(new LexicalIndex(), new InMemoryVectorStore(EMBEDDER), EMBEDDER, props.retrieval());
    }

    /** The bundled corpus, indexed as the application indexes it (hybrid retrieval). */
    public static Retriever corpusRetriever() {
        return corpusRetriever(PROPS);
    }

    public static Retriever corpusRetriever(RagProperties props) {
        Retriever r = retriever(props);
        Db db = database();
        new DocumentService(props, new DocumentRepository(db.jdbc(), db.tx()), r, new DocumentParser(),
                new PiiRedactor()).start();
        return r;
    }

    public static AnswerService pipeline(LlmClient llm) {
        return new AnswerService(SHARED_CORPUS, llm, new PiiRedactor(), new PromptInjectionDetector(),
                database().audit(), PROPS, null);
    }

    /** Built once: tests only read from it. */
    private static final Retriever SHARED_CORPUS = corpusRetriever();

    /** A PDF with one page per string, for upload tests. */
    public static byte[] pdf(List<String> pages) throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (String text : pages) {
                PDPage page = new PDPage();
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
                    cs.newLineAtOffset(50, 700);
                    for (String line : wrap(text, 80)) {
                        cs.showText(line);
                        cs.newLineAtOffset(0, -14);
                    }
                    cs.endText();
                }
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static List<String> wrap(String text, int width) {
        List<String> lines = new ArrayList<>();
        StringBuilder b = new StringBuilder();
        for (String w : text.split(" ")) {
            if (b.length() + w.length() + 1 > width) {
                lines.add(b.toString());
                b.setLength(0);
            }
            if (b.length() > 0) {
                b.append(' ');
            }
            b.append(w);
        }
        lines.add(b.toString());
        return lines;
    }
}
