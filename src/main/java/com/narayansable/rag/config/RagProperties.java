package com.narayansable.rag.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Everything tunable about retrieval and generation, from application.yml (prefix {@code rag}). */
@Validated
@ConfigurationProperties(prefix = "rag")
public record RagProperties(
        String corpus,
        @Min(200) int chunkSize,
        @Min(0) int chunkOverlap,
        @Min(1) @Max(20) int topK,
        double minScore,
        Llm llm,
        Audit audit) {

    public record Llm(String provider, String model, String effort, int maxTokens, int timeoutSeconds) {
    }

    public record Audit(String file) {
    }
}
