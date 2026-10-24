package com.narayansable.rag.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/** Everything tunable about retrieval, generation and access, from application.yml (prefix {@code rag}). */
@Validated
@ConfigurationProperties(prefix = "rag")
public record RagProperties(
        String corpus,
        @Min(200) int chunkSize,
        @Min(0) int chunkOverlap,
        @Min(1) @Max(20) int topK,
        @DecimalMin("0.0") @DecimalMax("1.0") double minScore,   // retrieval scores are on a 0..1 scale
        @NotNull @Valid Llm llm,
        @NotNull @Valid Retrieval retrieval,
        @NotNull @Valid Conversation conversation,
        @NotNull @Valid Security security) {

    public record Llm(String provider, String model, String effort, @Min(256) int maxTokens,
                      @Min(1) int timeoutSeconds) {
    }

    /**
     * How lexical and semantic evidence are blended. {@code semanticWeight} 0 turns semantic search
     * off; {@code vectorStore} is {@code memory} or {@code pgvector}.
     */
    public record Retrieval(@DecimalMin("0.0") @DecimalMax("1.0") double semanticWeight,
                            @DecimalMin("0.0") @DecimalMax("0.99") double semanticFloor,
                            String vectorStore) {

        public boolean semanticEnabled() {
            return semanticWeight > 0;
        }
    }

    /** Follow-up questions: how many earlier turns a request may carry, and how long each may be. */
    public record Conversation(@Min(0) @Max(10) int maxTurns, @Min(100) int maxTurnChars) {
    }

    /**
     * API keys. Each key belongs to a named caller with a role: {@code USER} may ask questions,
     * {@code ADMIN} may also manage documents and read the audit trail. {@code asksPerMinute} caps
     * each caller's questions - every one may cost a model call.
     */
    public record Security(List<ApiKey> apiKeys, @Min(1) int asksPerMinute) {

        /** {@code groups}: the access groups this caller may retrieve, beyond the open documents. */
        public record ApiKey(String name, String key, String role, List<String> groups) {

            @ConstructorBinding                      // the binder needs to know which constructor to use
            public ApiKey {
            }

            public ApiKey(String name, String key, String role) {
                this(name, key, role, List.of());
            }
        }
    }
}
