package com.narayansable.rag.index;

import com.narayansable.rag.TestSupport;
import com.narayansable.rag.ingest.Chunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RetrieverTest {

    private static Chunk c(String id, String section, String text) {
        return new Chunk(id + "#0", id, id, section, text, 0, 0);
    }

    private static Retriever lexical(Chunk... chunks) {
        var r = new Retriever(new LexicalIndex());
        for (Chunk c : chunks) {
            r.replaceDocument(c.documentId(), List.of(c));
        }
        return r;
    }

    @Test
    void exactRareTermWins() {
        var r = lexical(c("rtgs", "RTGS", "The minimum RTGS amount is Rs 2,00,000 with no upper limit."),
                c("neft", "NEFT", "NEFT settles in half-hourly batches with no minimum amount."),
                c("kyc", "KYC", "Re-KYC is due every ten years for low-risk customers."));
        var hits = r.search("What is the minimum amount for RTGS?", 3);
        assertThat(hits.get(0).chunk().documentId()).isEqualTo("rtgs");
        assertThat(hits.get(0).score()).isBetween(0.0, 1.0);
    }

    @Test
    void hyphenatedAndPluralFormsMeet() {
        var r = lexical(c("cut", "Cut-offs", "Branch cut-off for same-day processing is 4:30 PM."),
                c("other", "Other", "Interest is credited monthly."));
        assertThat(r.search("branch cutoff times", 1).get(0).chunk().documentId()).isEqualTo("cut");
    }

    @Test
    void emptyIndexAndUnknownTermsReturnNothing() {
        var r = new Retriever(new LexicalIndex());
        assertThat(r.search("anything", 3)).isEmpty();
        r.replaceDocument("a", List.of(c("a", "A", "NEFT batches settle half-hourly.")));
        assertThat(r.search("cryptocurrency staking yield", 3)).isEmpty();
    }

    @Test
    void replacingADocumentDoesNotDuplicateIt() {
        var r = lexical(c("rtgs", "RTGS", "The minimum RTGS amount is Rs 2,00,000."),
                c("neft", "NEFT", "NEFT settles in half-hourly batches."));
        r.replaceDocument("rtgs", List.of(c("rtgs", "RTGS", "The minimum RTGS amount is Rs 1,00,000.")));
        assertThat(r.size()).isEqualTo(2);
        assertThat(r.documentCount()).isEqualTo(2);
        var hits = r.search("minimum RTGS amount", 3);
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).chunk().text()).contains("1,00,000");
    }

    @Test
    void removingADocumentRemovesItFromBothIndexes() {
        var r = TestSupport.retriever(TestSupport.PROPS);
        r.replaceDocument("sweeps", List.of(c("sweeps", "Sweeps", "End-of-day sweeps run at 7:00 PM on working days.")));
        assertThat(r.search("when do sweeps run", 1)).isNotEmpty();
        r.removeDocument("sweeps");
        assertThat(r.search("when do sweeps run", 1)).isEmpty();
        assertThat(r.size()).isZero();
    }

    @Test
    void semanticSearchFindsAPassageWithNoWordsInCommon() {
        var r = TestSupport.retriever(TestSupport.PROPS);
        r.replaceDocument("sweeps", List.of(c("sweeps", "Sweep timings",
                "End-of-day sweeps run at 7:00 PM and move surplus balances into the header account.")));
        r.replaceDocument("kyc", List.of(c("kyc", "Re-KYC",
                "High-risk customers are re-verified every two years with fresh identity documents.")));
        var hits = r.search("When is spare cash moved between our accounts automatically?", 2);
        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).chunk().documentId()).isEqualTo("sweeps");
        assertThat(hits.get(0).semantic()).isPositive();
    }

    @Test
    void stemmerHandlesCommonSuffixes() {
        assertThat(Tokenizer.tokens("payments approved approving policies"))
                .containsExactly("payment", "approv", "approv", "policy");
        assertThat(Tokenizer.tokens("NEFT RTGS IFSC")).containsExactly("neft", "rtgs", "ifsc");
    }
}
