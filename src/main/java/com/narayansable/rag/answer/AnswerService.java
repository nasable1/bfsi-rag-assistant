package com.narayansable.rag.answer;

import com.narayansable.rag.audit.AuditTrail;
import com.narayansable.rag.config.RagProperties;
import com.narayansable.rag.guard.PiiRedactor;
import com.narayansable.rag.guard.PromptInjectionDetector;
import com.narayansable.rag.index.Retriever;
import com.narayansable.rag.index.ScoredChunk;
import com.narayansable.rag.llm.ExtractiveClient;
import com.narayansable.rag.llm.LlmClient;
import com.narayansable.rag.llm.LlmUnavailableException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The question-answering pipeline. Order is the design:
 * <ol>
 *   <li>Injection check on the raw question - refused before any retrieval or model call.</li>
 *   <li>PII redaction of the question and of any earlier turns sent with it.</li>
 *   <li>Follow-up rewrite into a standalone question, which is checked for injection again.</li>
 *   <li>Retrieval with a confidence floor - below it the assistant says it cannot find the answer
 *       rather than letting a model improvise from weak context.</li>
 *   <li>Generation, streamed - Claude when configured; if it is unreachable, the extractive answer,
 *       flagged.</li>
 *   <li>Citation check on the complete answer - every [n] must point at a supplied source; invented
 *       numbers are removed, and an answer left with no valid citation is not returned as fact.</li>
 *   <li>Hash-chained audit entry, and metrics.</li>
 * </ol>
 */
public class AnswerService {

    static final String NOT_FOUND = "I could not find this in the bank's documents. Please check with the product "
            + "or operations team.";
    static final String BLOCKED = "This request asks the assistant to change its instructions, so it was not "
            + "processed. Please ask a question about the documents.";
    private static final Pattern CITE = Pattern.compile("\\[(\\d{1,2})]");
    private static final Logger log = LoggerFactory.getLogger(AnswerService.class);

    /** A question and the earlier turns of its conversation (oldest first). */
    public record Question(String text, List<LlmClient.Turn> history) {
        public Question {
            history = history == null ? List.of() : List.copyOf(history);
        }

        public static Question of(String text) {
            return new Question(text, List.of());
        }
    }

    private final Retriever retriever;
    private final LlmClient llm;
    private final ExtractiveClient fallback = new ExtractiveClient();
    private final PiiRedactor redactor;
    private final PromptInjectionDetector injection;
    private final AuditTrail audit;
    private final RagProperties props;
    private final MeterRegistry metrics;

    public AnswerService(Retriever retriever, LlmClient llm, PiiRedactor redactor, PromptInjectionDetector injection,
                         AuditTrail audit, RagProperties props, MeterRegistry metrics) {
        this.retriever = retriever;
        this.llm = llm;
        this.redactor = redactor;
        this.injection = injection;
        this.audit = audit;
        this.props = props;
        this.metrics = metrics == null ? new SimpleMeterRegistry() : metrics;
    }

    public Answer ask(String question) {
        return ask(Question.of(question), "anonymous", delta -> {
        });
    }

    public Answer ask(Question q, String user, Consumer<String> onDelta) {
        long start = System.nanoTime();
        List<String> flags = new ArrayList<>();

        var redacted = redactor.redact(q.text());
        String question = redacted.text();
        Set<String> pii = new LinkedHashSet<>(redacted.found());
        if (injection.detect(q.text()).isPresent()) {
            flags.add("injection-blocked");
            addPiiFlag(flags, pii);
            return finish(user, question, null, BLOCKED, false, List.of(), flags, "guardrail", start);
        }

        List<LlmClient.Turn> history = new ArrayList<>();
        int maxTurns = props.conversation().maxTurns();
        int maxChars = props.conversation().maxTurnChars();
        for (LlmClient.Turn t : q.history().subList(Math.max(0, q.history().size() - maxTurns), q.history().size())) {
            // Redact first, then clip: clipping first could cut an identifier so it no longer matches.
            var tq = redactor.redact(t.question() == null ? "" : t.question());
            var ta = redactor.redact(t.answer() == null ? "" : t.answer());
            pii.addAll(tq.found());
            pii.addAll(ta.found());
            history.add(new LlmClient.Turn(clip(tq.text(), maxChars), clip(ta.text(), maxChars)));
        }
        addPiiFlag(flags, pii);

        String standalone = question;
        if (!history.isEmpty()) {
            standalone = redactor.redact(QueryRewriter.rewrite(question, history, llm)).text();
            if (!standalone.equals(question)) {
                flags.add("follow-up-rewritten");
                if (injection.detect(standalone).isPresent()) {
                    flags.add("injection-blocked");
                    return finish(user, question, standalone, BLOCKED, false, List.of(), flags, "guardrail", start);
                }
            }
        }
        String interpreted = standalone.equals(question) ? null : standalone;

        List<ScoredChunk> hits = retriever.search(standalone, props.topK());
        List<ScoredChunk> sources = hits.stream().filter(h -> h.score() >= props.minScore()).toList();
        if (sources.isEmpty()) {
            flags.add("low-confidence");
            return finish(user, question, interpreted, NOT_FOUND, false, List.of(), flags, "retrieval", start);
        }

        String model = llm.name();
        String raw;
        RedactingSink sink = new RedactingSink(onDelta);
        try {
            raw = llm.stream(standalone, sources, sink);
            sink.flush();
        } catch (LlmUnavailableException e) {
            log.warn("Model unavailable, answering extractively: {}", e.getMessage());
            flags.add("degraded-to-extractive");
            model = fallback.name();
            RedactingSink fallbackSink = new RedactingSink(onDelta);   // the failed partial is dropped
            raw = fallback.stream(standalone, sources, fallbackSink);
            fallbackSink.flush();
        }
        if (raw.strip().startsWith("NOT_IN_SOURCES")) {
            flags.add("not-in-sources");
            return finish(user, question, interpreted, NOT_FOUND, false, List.of(), flags, model, start);
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
            return finish(user, question, interpreted, NOT_FOUND, false, List.of(), flags, model, start);
        }
        List<Answer.Citation> citations = new ArrayList<>();
        for (int n : used) {
            ScoredChunk s = sources.get(n - 1);
            var c = s.chunk();
            citations.add(new Answer.Citation(n, c.documentId(), c.documentTitle(), c.section(),
                    c.page() > 0 ? c.page() : null, Math.round(s.score() * 1000) / 1000.0, excerpt(c.text())));
        }
        String text = cleaned.toString().replaceAll(" {2,}", " ").replaceAll(" +([.,;:])", "$1").strip();
        // The model's own words pass through the redactor too: it must not echo PII from a source.
        text = redactor.redact(text).text();
        return finish(user, question, interpreted, text, true, citations, flags, model, start);
    }

    /**
     * Passes streamed text on a sentence at a time, through the PII redactor, so the draft obeys the
     * same rule as the final answer. A sentence boundary (". ", "] ", a newline) never falls inside
     * an email, card, phone or account number, so no identifier is split between two flushes.
     */
    private final class RedactingSink implements Consumer<String> {
        private final Consumer<String> out;
        private final StringBuilder pending = new StringBuilder();

        RedactingSink(Consumer<String> out) {
            this.out = out;
        }

        @Override
        public void accept(String delta) {
            pending.append(delta);
            int cut = lastBoundary(pending);
            if (cut > 0) {
                out.accept(redactor.redact(pending.substring(0, cut)).text());
                pending.delete(0, cut);
            }
        }

        void flush() {
            if (pending.length() > 0) {
                out.accept(redactor.redact(pending.toString()).text());
                pending.setLength(0);
            }
        }
    }

    /** Index just after the last sentence end that is followed by whitespace, or 0. */
    static int lastBoundary(CharSequence s) {
        for (int i = s.length() - 2; i >= 0; i--) {
            char c = s.charAt(i);
            if ((c == '.' || c == '!' || c == '?' || c == ']' || c == '\n') && Character.isWhitespace(s.charAt(i + 1))) {
                return i + 2;
            }
        }
        return 0;
    }

    private static void addPiiFlag(List<String> flags, Set<String> pii) {
        if (!pii.isEmpty()) {
            flags.add("pii-redacted:" + String.join("+", pii));
        }
    }

    private static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String excerpt(String text) {
        if (text.length() <= 220) {
            return text;
        }
        int cut = text.lastIndexOf(' ', 220);
        return text.substring(0, cut > 150 ? cut : 220) + "...";
    }

    private Answer finish(String user, String question, String interpretedAs, String answer, boolean grounded,
                          List<Answer.Citation> citations, List<String> flags, String model, long start) {
        List<String> refs = citations.stream()
                .map(c -> c.document() + " / " + c.section() + (c.page() == null ? "" : " / p." + c.page()))
                .toList();
        var entry = audit.record(user, question, interpretedAs, answer, refs, model, flags);
        long nanos = System.nanoTime() - start;
        record(grounded, flags, model, nanos);
        return new Answer(question, answer, grounded, citations, List.copyOf(flags), model, interpretedAs, entry.seq(),
                nanos / 1_000_000);
    }

    /** Outcome counters, per-flag counters and a latency timer - see /actuator/prometheus. */
    private void record(boolean grounded, List<String> flags, String model, long nanos) {
        String outcome = grounded ? "answered"
                : flags.contains("injection-blocked") ? "blocked"
                : flags.contains("low-confidence") ? "not-found-retrieval"
                : flags.contains("uncited-answer-withheld") ? "withheld"
                : "not-found-model";
        String modelTag = model.startsWith("claude:") ? "claude" : model;
        metrics.counter("rag.answers", "outcome", outcome, "model", modelTag).increment();
        for (String f : flags) {
            metrics.counter("rag.guardrail.flags", "flag", f.startsWith("pii-redacted") ? "pii-redacted" : f)
                    .increment();
        }
        Timer.builder("rag.answer.latency").tag("outcome", outcome).publishPercentileHistogram()
                .register(metrics).record(nanos, TimeUnit.NANOSECONDS);
    }
}
