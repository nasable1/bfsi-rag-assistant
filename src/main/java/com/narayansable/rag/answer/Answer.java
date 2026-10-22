package com.narayansable.rag.answer;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * What the assistant returns. {@code grounded} is false whenever the answer is a refusal (blocked,
 * nothing relevant found, or the model said the sources do not cover it). {@code flags} records
 * every guardrail that fired - pii-redacted, injection-blocked, low-confidence, citations-repaired,
 * degraded-to-extractive, follow-up-rewritten - so callers and auditors see why the answer looks
 * the way it does. {@code question} is the question as processed - PII-redacted - which is what a
 * client should keep as conversation history (never the raw text). {@code interpretedAs} is the
 * standalone question a follow-up was rewritten to.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Answer(String question, String answer, boolean grounded, List<Citation> citations, List<String> flags, String model,
                     String interpretedAs, long auditSeq, long latencyMs) {

    /** {@code page} is null for sources without pages. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Citation(int number, String documentId, String document, String section, Integer page, double score,
                           String excerpt) {
    }
}
