package com.narayansable.rag.answer;

import com.narayansable.rag.audit.AuditTrail;
import com.narayansable.rag.config.RagProperties;
import com.narayansable.rag.guard.PiiRedactor;
import com.narayansable.rag.guard.PromptInjectionDetector;
import com.narayansable.rag.index.HybridIndex;
import com.narayansable.rag.index.ScoredChunk;
import com.narayansable.rag.llm.ExtractiveClient;
import com.narayansable.rag.llm.LlmClient;
import com.narayansable.rag.llm.LlmUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The question-answering pipeline. Order is the design:
 * <ol>
 *   <li>Injection check on the raw question - refused before any retrieval or model call.</li>
 *   <li>PII redaction - only the redacted question is searched, sent to a model or audited.</li>
 *   <li>Retrieval with a confidence floor - below it the assistant says it cannot find the answer
 *       rather than letting a model improvise from weak context.</li>
 *   <li>Generation - Claude when configured; if it is unreachable, the extractive answer, flagged.</li>
 *   <li>Citation check - every [n] must point at a source that was supplied; invented numbers are
 *       removed, and an answer left with no valid citation is not returned as fact.</li>
 *   <li>Hash-chained audit entry.</li>
 * </ol>
 */
@Service
public class AnswerService {

    static final String NOT_FOUND = "I could not find this in the bank's documents. Please check with the product "
            + "or operations team.";
    static final String BLOCKED = "This request asks the assistant to change its instructions, so it was not "
            + "processed. Please ask a question about the documents.";
    private static final Pattern CITE = Pattern.compile("\\[(\\d{1,2})]");
    private static final Logger log = LoggerFactory.getLogger(AnswerService.class);

    private final HybridIndex index;
    private final LlmClient llm;
    private final ExtractiveClient fallback = new ExtractiveClient();
    private final PiiRedactor redactor;
    private final PromptInjectionDetector injection;
    private final AuditTrail audit;
    private final RagProperties props;

    public AnswerService(HybridIndex index, LlmClient llm, PiiRedactor redactor, PromptInjectionDetector injection,
                         AuditTrail audit, RagProperties props) {
        this.index = index;
        this.llm = llm;
        this.redactor = redactor;
        this.injection = injection;
        this.audit = audit;
        this.props = props;
    }

    public Answer ask(String rawQuestion) {
        long start = System.nanoTime();
        List<String> flags = new ArrayList<>();

        var attack = injection.detect(rawQuestion);
        var redacted = redactor.redact(rawQuestion);
        String question = redacted.text();
        if (redacted.redacted()) {
            flags.add("pii-redacted:" + String.join("+", redacted.found()));
        }
        if (attack.isPresent()) {
            flags.add("injection-blocked");
            return finish(question, BLOCKED, false, List.of(), flags, "guardrail", start);
        }

        List<ScoredChunk> hits = index.search(question, props.topK());
        List<ScoredChunk> sources = hits.stream().filter(h -> h.score() >= props.minScore()).toList();
        if (sources.isEmpty()) {
            flags.add("low-confidence");
            return finish(question, NOT_FOUND, false, List.of(), flags, "retrieval", start);
        }

        String model = llm.name();
        String raw;
        try {
            raw = llm.answer(question, sources);
        } catch (LlmUnavailableException e) {
            log.warn("Model unavailable, answering extractively: {}", e.getMessage());
            flags.add("degraded-to-extractive");
            model = fallback.name();
            raw = fallback.answer(question, sources);
        }
        if (raw.strip().startsWith("NOT_IN_SOURCES")) {
            flags.add("not-in-sources");
            return finish(question, NOT_FOUND, false, List.of(), flags, model, start);
        }

        // Keep only citations that point at a supplied source.
        Set<Integer> used = new LinkedHashSet<>();
        StringBuilder cleaned = new StringBuilder();
        Matcher m = CITE.matcher(raw);
        boolean repaired = false;
        while (m.find()) {
            int n = Integer.parseInt(m.group(1));
            if (n >= 1 && n <= sources.size()) {
                used.add(n);
                m.appendReplacement(cleaned, Matcher.quoteReplacement(m.group()));
            } else {
                repaired = true;
                m.appendReplacement(cleaned, "");
            }
        }
        m.appendTail(cleaned);
        if (repaired) {
            flags.add("citations-repaired");
        }
        if (used.isEmpty()) {
            flags.add("uncited-answer-withheld");
            return finish(question, NOT_FOUND, false, List.of(), flags, model, start);
        }
        List<Answer.Citation> citations = new ArrayList<>();
        for (int n : used) {
            ScoredChunk s = sources.get(n - 1);
            String excerpt = s.chunk().text().length() > 220 ? s.chunk().text().substring(0, 220) + "..." : s.chunk().text();
            citations.add(new Answer.Citation(n, s.chunk().documentTitle(), s.chunk().section(),
                    Math.round(s.score() * 1000) / 1000.0, excerpt));
        }
        return finish(question, cleaned.toString().replaceAll(" {2,}", " ").strip(), true, citations, flags, model, start);
    }

    private Answer finish(String question, String answer, boolean grounded, List<Answer.Citation> citations,
                          List<String> flags, String model, long start) {
        List<String> refs = citations.stream().map(c -> c.document() + " / " + c.section()).toList();
        var entry = audit.record(question, answer, refs, model, flags);
        return new Answer(answer, grounded, citations, List.copyOf(flags), model, entry.seq(),
                (System.nanoTime() - start) / 1_000_000);
    }
}
