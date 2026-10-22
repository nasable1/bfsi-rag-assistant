package com.narayansable.rag.answer;

import com.narayansable.rag.index.ScoredChunk;
import com.narayansable.rag.llm.ExtractiveClient;
import com.narayansable.rag.llm.LlmClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class QueryRewriterTest {

    private static final List<LlmClient.Turn> HISTORY =
            List.of(new LlmClient.Turn("How often is re-KYC due for high-risk customers?", "Every two years [1]."));

    @Test
    void standaloneQuestionsAreLeftAlone() {
        assertThat(QueryRewriter.rewrite("When do end-of-day sweeps run?", HISTORY, new ExtractiveClient()))
                .isEqualTo("When do end-of-day sweeps run?");
        assertThat(QueryRewriter.rewrite("and?", List.of(), new ExtractiveClient())).isEqualTo("and?");
    }

    @Test
    void offlineFollowUpsAreSearchedWithThePreviousQuestion() {
        assertThat(QueryRewriter.rewrite("What about low-risk ones?", HISTORY, new ExtractiveClient()))
                .isEqualTo("How often is re-KYC due for high-risk customers? What about low-risk ones?");
        assertThat(QueryRewriter.looksLikeFollowUp("And above Rs 5 crore?")).isTrue();
        assertThat(QueryRewriter.looksLikeFollowUp("Is that allowed?")).isTrue();
        assertThat(QueryRewriter.looksLikeFollowUp("Medium risk?")).isTrue();
        assertThat(QueryRewriter.looksLikeFollowUp("Who approves an override of the approval matrix?")).isFalse();
    }

    @Test
    void theModelsRewriteWinsWhenItHasOne() {
        LlmClient model = new LlmClient() {
            public String name() {
                return "m";
            }

            public String answer(String q, List<ScoredChunk> s) {
                return "";
            }

            @Override
            public Optional<String> rewrite(String question, List<Turn> history) {
                return Optional.of("  How often is re-KYC due for low-risk customers?  ");
            }
        };
        assertThat(QueryRewriter.rewrite("What about low-risk ones?", HISTORY, model))
                .isEqualTo("How often is re-KYC due for low-risk customers?");
    }
}
