package com.narayansable.rag.llm;

import com.narayansable.rag.index.ScoredChunk;
import com.narayansable.rag.index.Tokenizer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * No model at all: picks the source sentences that share the most terms with the question and
 * returns them with their citation numbers. Deterministic, free and offline - the default when no
 * API key is configured, and what the tests run against. It never paraphrases, so it cannot
 * hallucinate; it can only under-answer.
 */
public class ExtractiveClient implements LlmClient {

    private static final int MAX_SENTENCES = 3;

    @Override
    public String name() {
        return "extractive";
    }

    /** Hands the answer over one cited sentence at a time, as a model would stream it. */
    @Override
    public String stream(String question, List<ScoredChunk> sources, java.util.function.Consumer<String> onDelta) {
        String answer = answer(question, sources);
        String[] sentences = answer.split("(?<=]) ");
        for (int i = 0; i < sentences.length; i++) {
            onDelta.accept(i < sentences.length - 1 ? sentences[i] + " " : sentences[i]);
        }
        return answer;
    }

    @Override
    public String answer(String question, List<ScoredChunk> sources) {
        Set<String> q = new HashSet<>(Tokenizer.tokens(question));
        record Candidate(String sentence, int source, double overlap, int order) {
        }
        List<Candidate> all = new ArrayList<>();
        int order = 0;
        for (int i = 0; i < sources.size(); i++) {
            for (String s : sources.get(i).chunk().text().split("(?<=[.!?])\\s+|\\n+")) {
                String sentence = s.strip().replaceFirst("^[-*\\d.)\\s]+", "");
                if (sentence.length() < 25) {
                    continue;
                }
                Set<String> t = new HashSet<>(Tokenizer.tokens(sentence));
                t.retainAll(q);
                if (!t.isEmpty()) {
                    all.add(new Candidate(sentence, i + 1, t.size() / Math.sqrt(1 + sentence.length() / 80.0), order));
                }
                order++;
            }
        }
        if (all.isEmpty()) {
            return "NOT_IN_SOURCES";
        }
        List<Candidate> best = all.stream()
                .sorted(Comparator.comparingDouble(Candidate::overlap).reversed())
                .limit(MAX_SENTENCES)
                .sorted(Comparator.comparingInt(Candidate::order))
                .toList();
        StringBuilder b = new StringBuilder();
        for (Candidate c : best) {
            String s = c.sentence().endsWith(".") ? c.sentence() : c.sentence() + ".";
            b.append(s).append(" [").append(c.source()).append("] ");
        }
        return b.toString().strip();
    }
}
