package com.narayansable.rag.index;

import com.narayansable.rag.ingest.Chunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HybridIndexTest {

    private static Chunk c(String id, String section, String text) {
        return new Chunk(id, id, id, section, text, 0);
    }

    @Test
    void exactRareTermWins() {
        var index = new HybridIndex();
        index.addAll(List.of(
                c("rtgs", "RTGS", "The minimum RTGS amount is Rs 2,00,000 with no upper limit."),
                c("neft", "NEFT", "NEFT settles in half-hourly batches with no minimum amount."),
                c("kyc", "KYC", "Re-KYC is due every ten years for low-risk customers.")));
        var hits = index.search("What is the minimum amount for RTGS?", 3);
        assertThat(hits.get(0).chunk().id()).isEqualTo("rtgs");
        assertThat(hits.get(0).score()).isBetween(0.0, 1.0);
    }

    @Test
    void hyphenatedAndPluralFormsMeet() {
        var index = new HybridIndex();
        index.addAll(List.of(c("cut", "Cut-offs", "Branch cut-off for same-day processing is 4:30 PM."),
                c("other", "Other", "Interest is credited monthly.")));
        assertThat(index.search("branch cutoff times", 1).get(0).chunk().id()).isEqualTo("cut");
    }

    @Test
    void emptyIndexAndUnknownTermsReturnNothing() {
        var index = new HybridIndex();
        assertThat(index.search("anything", 3)).isEmpty();
        index.addAll(List.of(c("a", "A", "NEFT batches settle half-hourly.")));
        assertThat(index.search("cryptocurrency staking yield", 3)).isEmpty();
    }

    @Test
    void stemmerHandlesCommonSuffixes() {
        assertThat(Tokenizer.tokens("payments approved approving policies"))
                .containsExactly("payment", "approv", "approv", "policy");
        assertThat(Tokenizer.tokens("NEFT RTGS IFSC")).containsExactly("neft", "rtgs", "ifsc");
    }
}
