package com.narayansable.rag.answer;

import java.util.List;

/**
 * What the assistant returns. {@code grounded} is false whenever the answer is a refusal (blocked,
 * nothing relevant found, or the model said the sources do not cover it). {@code flags} records
 * every guardrail that fired - pii-redacted, injection-blocked, low-confidence, citations-repaired,
 * degraded-to-extractive - so callers and auditors see why the answer looks the way it does.
 */
public record Answer(String answer, boolean grounded, List<Citation> citations, List<String> flags, String model,
                     long auditSeq, long latencyMs) {

    public record Citation(int number, String document, String section, double score, String excerpt) {
    }
}
