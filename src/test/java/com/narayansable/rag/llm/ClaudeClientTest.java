package com.narayansable.rag.llm;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.narayansable.rag.index.ScoredChunk;
import com.narayansable.rag.ingest.Chunk;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ClaudeClient against a local fake of the Messages API speaking the real wire format, so the SDK
 * calls, the request we send and the handling of every kind of reply are tested without a key.
 */
class ClaudeClientTest {

    private static final ScoredChunk SOURCE = new ScoredChunk(
            new Chunk("d#0", "d", "Doc", "Sweeps", "End-of-day sweeps run at 7:00 PM.", 0, 3), 0.5, 1, 0.5);
    private final ObjectMapper json = new ObjectMapper();

    private HttpServer server;
    private final List<JsonNode> bodies = new CopyOnWriteArrayList<>();
    private final List<String> betas = new CopyOnWriteArrayList<>();
    private volatile int status = 200;
    private volatile String reply = "";
    private volatile String contentType = "text/event-stream";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", ex -> {
            bodies.add(json.readTree(ex.getRequestBody()));
            betas.add(String.valueOf(ex.getRequestHeaders().getFirst("anthropic-beta")));
            byte[] out = reply.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", status == 200 ? contentType : "application/json");
            ex.sendResponseHeaders(status, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private ClaudeClient claude() {
        var sdk = AnthropicOkHttpClient.builder().apiKey("test-key")
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .maxRetries(0).timeout(Duration.ofSeconds(10)).build();
        return new ClaudeClient(sdk, "claude-opus-5-5", 16000, "medium");
    }

    private static String sse(String stopReason, String... deltas) {
        StringBuilder b = new StringBuilder();
        event(b, "message_start", "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"type\":\"message\","
                + "\"role\":\"assistant\",\"model\":\"claude-opus-5-5\",\"content\":[],\"stop_reason\":null,"
                + "\"stop_sequence\":null,\"usage\":{\"input_tokens\":12,\"output_tokens\":1}}}");
        if (deltas.length > 0) {
            event(b, "content_block_start", "{\"type\":\"content_block_start\",\"index\":0,"
                    + "\"content_block\":{\"type\":\"text\",\"text\":\"\"}}");
            for (String d : deltas) {
                event(b, "content_block_delta", "{\"type\":\"content_block_delta\",\"index\":0,"
                        + "\"delta\":{\"type\":\"text_delta\",\"text\":\"" + d + "\"}}");
            }
            event(b, "content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}");
        }
        event(b, "message_delta", "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"" + stopReason
                + "\",\"stop_sequence\":null},\"usage\":{\"output_tokens\":9}}");
        event(b, "message_stop", "{\"type\":\"message_stop\"}");
        return b.toString();
    }

    private static void event(StringBuilder b, String name, String data) {
        b.append("event: ").append(name).append('\n').append("data: ").append(data).append("\n\n");
    }

    @Test
    void streamsTextAndReturnsTheCompleteAnswer() {
        reply = sse("end_turn", "End-of-day sweeps ", "run at 7:00 PM [1].");
        List<String> deltas = new ArrayList<>();
        String answer = claude().stream("When do sweeps run?", List.of(SOURCE), deltas::add);

        assertThat(deltas).containsExactly("End-of-day sweeps ", "run at 7:00 PM [1].");
        assertThat(answer).isEqualTo("End-of-day sweeps run at 7:00 PM [1].");
    }

    @Test
    void sendsTheModelEffortRefusalFallbackAndNumberedSources() {
        reply = sse("end_turn", "ok [1]");
        claude().answer("When do sweeps run?", List.of(SOURCE));

        JsonNode body = bodies.get(0);
        assertThat(body.get("model").asText()).isEqualTo("claude-opus-5-5");
        assertThat(body.get("max_tokens").asInt()).isEqualTo(16000);
        assertThat(body.get("stream").asBoolean()).isTrue();
        assertThat(body.at("/output_config/effort").asText()).isEqualTo("medium");
        assertThat(body.get("fallbacks").asText()).isEqualTo("default");
        assertThat(betas.get(0)).contains(ClaudeClient.FALLBACK_BETA);
        assertThat(body.get("system").toString()).contains("NOT_IN_SOURCES");
        assertThat(body.at("/messages/0/content").toString())
                .contains("[1] Doc - Sweeps (page 3)").contains("Question: When do sweeps run?");
    }

    @Test
    void aRefusalIsReportedAsNotInSources() {
        reply = sse("refusal", "Partial text that must not be shown");
        assertThat(claude().answer("q", List.of(SOURCE))).isEqualTo("NOT_IN_SOURCES");
    }

    @Test
    void aTruncatedAnswerIsTreatedAsUnavailable() {
        reply = sse("max_tokens", "Sweeps run at");
        assertThatThrownBy(() -> claude().answer("q", List.of(SOURCE)))
                .isInstanceOf(LlmUnavailableException.class).hasMessageContaining("truncated");
    }

    @Test
    void anEmptyReplyIsNotInSources() {
        reply = sse("end_turn");
        assertThat(claude().answer("q", List.of(SOURCE))).isEqualTo("NOT_IN_SOURCES");
    }

    @Test
    void anHttpErrorIsReportedAsUnavailable() {
        status = 529;
        reply = "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}";
        assertThatThrownBy(() -> claude().answer("q", List.of(SOURCE)))
                .isInstanceOf(LlmUnavailableException.class).hasMessageContaining("529");
    }

    @Test
    void unreachableApiIsReportedAsUnavailable() {
        // Nothing listens on port 9: the SDK fails with a connection error, not an HTTP status.
        var sdk = AnthropicOkHttpClient.builder().apiKey("test-key").baseUrl("http://127.0.0.1:9")
                .maxRetries(0).timeout(Duration.ofSeconds(5)).build();
        var claude = new ClaudeClient(sdk, "claude-opus-5-5", 1000, "medium");
        assertThatThrownBy(() -> claude.answer("question", List.of(SOURCE)))
                .isInstanceOf(LlmUnavailableException.class);
    }

    @Test
    void rewritesAFollowUpAtLowEffortWithTheConversationAsData() {
        contentType = "application/json";
        reply = "{\"id\":\"msg_2\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-opus-5-5\","
                + "\"content\":[{\"type\":\"text\",\"text\":\"What is the IMPS limit per transaction?\"}],"
                + "\"stop_reason\":\"end_turn\",\"stop_sequence\":null,\"usage\":{\"input_tokens\":5,\"output_tokens\":5}}";
        var out = claude().rewrite("And for IMPS?",
                List.of(new LlmClient.Turn("What is the RTGS limit?", "No upper limit [1].")));

        assertThat(out).contains("What is the IMPS limit per transaction?");
        JsonNode body = bodies.get(0);
        assertThat(body.at("/output_config/effort").asText()).isEqualTo("low");
        assertThat(body.get("fallbacks").asText()).isEqualTo("default");
        assertThat(body.at("/messages/0/content").asText())
                .contains("User: What is the RTGS limit?").contains("Latest question: And for IMPS?");
    }

    @Test
    void aFailedRewriteFallsBackQuietly() {
        status = 500;
        reply = "{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"boom\"}}";
        assertThat(claude().rewrite("and?", List.of(new LlmClient.Turn("q", "a")))).isEmpty();
    }
}
