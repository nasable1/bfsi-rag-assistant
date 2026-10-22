package com.narayansable.rag;

import com.narayansable.rag.answer.AnswerService;
import com.narayansable.rag.audit.AuditTrail;
import com.narayansable.rag.config.RagProperties;
import com.narayansable.rag.guard.PiiRedactor;
import com.narayansable.rag.guard.PromptInjectionDetector;
import com.narayansable.rag.index.HybridIndex;
import com.narayansable.rag.ingest.CorpusLoader;
import com.narayansable.rag.llm.LlmClient;

/** The production pipeline over the bundled corpus, with no Spring context and a pluggable model. */
public final class TestSupport {

    public static final RagProperties PROPS = new RagProperties("classpath*:corpus/*.md", 700, 120, 4, 0.15,
            new RagProperties.Llm("extractive", "test", "medium", 700, 10), new RagProperties.Audit(""));

    private TestSupport() {
    }

    public static HybridIndex corpusIndex() {
        var index = new HybridIndex();
        try {
            new CorpusLoader(PROPS, index, new PiiRedactor()).load();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return index;
    }

    public static AnswerService pipeline(LlmClient llm) {
        return new AnswerService(corpusIndex(), llm, new PiiRedactor(), new PromptInjectionDetector(),
                new AuditTrail(PROPS), PROPS);
    }
}
