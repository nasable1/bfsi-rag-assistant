package com.narayansable.rag;

import com.narayansable.rag.config.RagProperties;
import com.narayansable.rag.guard.PiiRedactor;
import com.narayansable.rag.index.LexicalIndex;
import com.narayansable.rag.index.Retriever;
import com.narayansable.rag.ingest.DocumentParser;
import com.narayansable.rag.ingest.DocumentRepository;
import com.narayansable.rag.ingest.DocumentService;
import com.narayansable.rag.llm.LlmConfig;
import com.narayansable.rag.security.ApiKeyRegistry;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Regressions from the 22 Oct 2026 review. */
class ReviewFixesTest {

    @Test
    void oneKeyForTwoCallersFailsAtStartUp() {
        var same = "shared-key-0123456789";
        assertThatThrownBy(() -> new ApiKeyRegistry(new RagProperties.Security(List.of(
                new RagProperties.Security.ApiKey("teller", same, "USER"),
                new RagProperties.Security.ApiKey("ops", same, "ADMIN")), 30)))
                .hasMessageContaining("same as another caller");
    }

    @Test
    void anInvalidEffortFailsAtStartUp() {
        var p = TestSupport.PROPS;
        var bad = new RagProperties(p.corpus(), p.chunkSize(), p.chunkOverlap(), p.topK(), p.minScore(),
                new RagProperties.Llm("extractive", "test", "hgh", 700, 10), p.retrieval(), p.conversation(),
                p.security());
        assertThatThrownBy(() -> new LlmConfig().llmClient(bad)).hasMessageContaining("rag.llm.effort");
        assertThat(new LlmConfig().llmClient(p).name()).isEqualTo("extractive");
    }

    @Test
    void aLongTitleOrFileNameIsClippedNotRejected() {
        var db = TestSupport.database();
        var service = new DocumentService(TestSupport.PROPS, new DocumentRepository(db.jdbc(), db.tx()),
                new Retriever(new LexicalIndex()), new DocumentParser(), new PiiRedactor());
        String heading = "Demo Bank - " + "very long policy title ".repeat(30);
        var stored = service.ingestFile("long-title", "x".repeat(300) + ".md",
                ("# " + heading + "\n\nPayments above Rs 25,00,000 need two checkers.").getBytes(StandardCharsets.UTF_8),
                "admin");
        assertThat(stored.title()).hasSizeLessThanOrEqualTo(300).endsWith("...");
        var listed = service.list().get(0);
        assertThat(listed.title()).isEqualTo(stored.title());
        assertThat(listed.fileName()).hasSizeLessThanOrEqualTo(255);
    }
}
