package com.narayansable.rag.llm;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.narayansable.rag.index.ScoredChunk;
import com.narayansable.rag.ingest.Chunk;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClaudeClientTest {

    @Test
    void unreachableApiIsReportedAsUnavailableSoTheServiceCanDegrade() {
        // Nothing listens on port 9: the SDK fails with a connection error, not an HTTP status.
        var sdk = AnthropicOkHttpClient.builder().apiKey("test-key").baseUrl("http://127.0.0.1:9")
                .maxRetries(0).timeout(Duration.ofSeconds(5)).build();
        var claude = new ClaudeClient(sdk, "claude-opus-5-5", 1000, "medium");
        var source = new ScoredChunk(new Chunk("d#0", "d", "Doc", "S", "Text.", 0), 0.5, 1, 0.5);
        assertThatThrownBy(() -> claude.answer("question", List.of(source)))
                .isInstanceOf(LlmUnavailableException.class);
    }
}
