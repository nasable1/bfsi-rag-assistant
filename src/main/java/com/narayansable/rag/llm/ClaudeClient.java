package com.narayansable.rag.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.beta.messages.BetaMessage;
import com.anthropic.models.beta.messages.BetaOutputConfig;
import com.anthropic.models.beta.messages.BetaStopReason;
import com.anthropic.models.beta.messages.MessageCreateParams;
import com.narayansable.rag.index.ScoredChunk;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Claude through the official Anthropic Java SDK.
 *
 * <ul>
 *   <li>The SDK retries rate limits (429), overload (529), server errors and connection failures
 *       with backoff; whatever still fails - an HTTP error, a timeout, a dropped connection - surfaces
 *       as {@link LlmUnavailableException}, and the answer service degrades to the extractive answer
 *       instead of failing the request.</li>
 *   <li>An answer cut off by {@code max_tokens} is not returned: its last sentence - often the one
 *       with the figure - may be missing, so it is treated like an outage.</li>
 *   <li>Server-side refusal fallback is on ({@code fallbacks: "default"}): if a safety classifier
 *       declines, the API re-runs the request on a suitable model within the same call. A refusal
 *       that survives the fallback is reported as {@code NOT_IN_SOURCES}, never as content.</li>
 *   <li>Effort is set explicitly - grounded question answering over a few chunks rarely needs more
 *       than {@code medium}, and it is the main cost/latency lever.</li>
 * </ul>
 */
public class ClaudeClient implements LlmClient {

    static final String FALLBACK_BETA = "server-side-fallback-2026-07-01";

    private final AnthropicClient client;
    private final String model;
    private final long maxTokens;
    private final BetaOutputConfig.Effort effort;

    public ClaudeClient(AnthropicClient client, String model, long maxTokens, String effort) {
        this.client = client;
        this.model = model;
        this.maxTokens = maxTokens;
        this.effort = BetaOutputConfig.Effort.of(effort.toLowerCase(Locale.ROOT));
    }

    @Override
    public String name() {
        return "claude:" + model;
    }

    @Override
    public String answer(String question, List<ScoredChunk> sources) {
        MessageCreateParams params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(maxTokens)
                .system(SYSTEM_PROMPT)
                .outputConfig(BetaOutputConfig.builder().effort(effort).build())
                .addUserMessage("Sources:\n\n" + LlmClient.sourcesBlock(sources) + "Question: " + question)
                .addBeta(FALLBACK_BETA)
                .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                .build();
        BetaMessage message;
        try {
            message = client.beta().messages().create(params);
        } catch (AnthropicServiceException e) {
            throw new LlmUnavailableException("Claude returned HTTP " + e.statusCode() + ": " + e.getMessage(), e);
        } catch (AnthropicException e) {
            // Network failure, timeout or retries exhausted - not an HTTP error, but still an outage.
            throw new LlmUnavailableException("Claude unreachable: " + e.getMessage(), e);
        }
        var stop = message.stopReason().orElse(null);
        if (BetaStopReason.REFUSAL.equals(stop)) {
            return "NOT_IN_SOURCES";
        }
        if (BetaStopReason.MAX_TOKENS.equals(stop)) {
            throw new LlmUnavailableException("Claude answer truncated at max_tokens=" + maxTokens, null);
        }
        String text = message.content().stream()
                .flatMap(block -> block.text().stream())
                .map(t -> t.text())
                .collect(Collectors.joining("\n"))
                .strip();
        return text.isEmpty() ? "NOT_IN_SOURCES" : text;
    }
}
