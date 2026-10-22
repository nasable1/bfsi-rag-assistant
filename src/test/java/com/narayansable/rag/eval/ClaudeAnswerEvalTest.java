package com.narayansable.rag.eval;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.narayansable.rag.TestSupport;
import com.narayansable.rag.llm.ClaudeClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The same golden set against Claude. It calls the API ({@code ~46} requests), so it runs only
 * when asked for: {@code ANTHROPIC_API_KEY=... mvn test -Dtest=ClaudeAnswerEvalTest -Deval.claude=true}.
 * The report lands in target/eval/answer-eval-claude.md.
 */
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
@EnabledIfSystemProperty(named = "eval.claude", matches = "true")
class ClaudeAnswerEvalTest {

    @Test
    void claudeAnswersMeetTheBar() {
        String model = System.getProperty("eval.model", "claude-opus-5-5");
        var client = AnthropicOkHttpClient.builder().fromEnv().timeout(Duration.ofSeconds(120)).maxRetries(3).build();
        var claude = new ClaudeClient(client, model, 16000, System.getProperty("eval.effort", "medium"));
        var report = AnswerEvaluator.run(TestSupport.pipeline(claude), "claude:" + model);
        report.write(Path.of("target", "eval", "answer-eval-claude.md"));
        System.out.println(report.summary());
        assertThat(report.factAccuracy()).as("fact accuracy").isGreaterThanOrEqualTo(0.9);
        assertThat(report.citationAccuracy()).as("citation accuracy").isGreaterThanOrEqualTo(0.95);
        assertThat(report.refusalAccuracy()).as("refusal accuracy").isGreaterThanOrEqualTo(1.0);
    }
}
