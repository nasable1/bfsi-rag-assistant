package com.narayansable.rag.llm;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.narayansable.rag.config.RagProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * Chooses the generator. {@code rag.llm.provider}: {@code claude}, {@code extractive}, or
 * {@code auto} (Claude when ANTHROPIC_API_KEY is set, otherwise extractive - so the service and its
 * tests run with no key and no network).
 */
@Configuration
public class LlmConfig {

    private static final Logger log = LoggerFactory.getLogger(LlmConfig.class);

    @Bean
    public LlmClient llmClient(RagProperties props) {
        var llm = props.llm();
        String provider = llm.provider() == null ? "auto" : llm.provider().strip().toLowerCase(Locale.ROOT);
        if (!List.of("auto", "claude", "extractive").contains(provider)) {
            // Fail at start-up: a typo must not silently switch a deployment to offline answers.
            throw new IllegalStateException("rag.llm.provider must be auto, claude or extractive, not '"
                    + llm.provider() + "'");
        }
        String effort = llm.effort() == null ? "" : llm.effort().strip().toLowerCase(Locale.ROOT);
        if (!List.of("low", "medium", "high", "xhigh", "max").contains(effort)) {
            // Same reason: an invalid effort would fail every call and quietly degrade every answer.
            throw new IllegalStateException("rag.llm.effort must be low, medium, high, xhigh or max, not '"
                    + llm.effort() + "'");
        }
        String key = System.getenv("ANTHROPIC_API_KEY");
        boolean haveKey = key != null && !key.isBlank();
        boolean useClaude = provider.equals("claude") || (provider.equals("auto") && haveKey);
        if (!useClaude) {
            log.info("Answer generation: extractive (no model call)");
            return new ExtractiveClient();
        }
        var client = AnthropicOkHttpClient.builder()
                .fromEnv()
                .timeout(Duration.ofSeconds(llm.timeoutSeconds()))
                .maxRetries(3)
                .build();
        log.info("Answer generation: {} (effort {})", llm.model(), effort);
        return new ClaudeClient(client, llm.model(), llm.maxTokens(), effort);
    }
}
