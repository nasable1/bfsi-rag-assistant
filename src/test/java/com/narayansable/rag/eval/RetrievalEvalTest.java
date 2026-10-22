package com.narayansable.rag.eval;

import com.narayansable.rag.TestSupport;
import com.narayansable.rag.index.HybridIndex;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Retrieval quality gate. Each golden question names the document that must answer it; the build
 * fails if hit@1 or hit@3 drops below the bar - so a tokenizer, chunking or weighting change that
 * quietly hurts retrieval is caught before it ships. Recall is measured on retrieval alone: if the
 * right source is not retrieved, no model can answer correctly.
 */
class RetrievalEvalTest {

    private static final Map<String, String> GOLDEN = Map.ofEntries(
            Map.entry("What is the minimum amount for RTGS?", "payments-cutoffs"),
            Map.entry("How long does a NEFT return to the remitter take?", "payments-cutoffs"),
            Map.entry("What is the IMPS limit for a newly added beneficiary?", "payments-cutoffs"),
            Map.entry("Branch cut-off for same-day processing", "payments-cutoffs"),
            Map.entry("How many checkers are required for a payment of Rs 3 crore?", "maker-checker-policy"),
            Map.entry("Can a maker approve their own payment?", "maker-checker-policy"),
            Map.entry("What happens to an entry not approved in 24 hours?", "maker-checker-policy"),
            Map.entry("When do end-of-day sweeps run?", "liquidity-sweeps"),
            Map.entry("Is notional pooling offered?", "liquidity-sweeps"),
            Map.entry("What is a target balance sweep?", "liquidity-sweeps"),
            Map.entry("How often is re-KYC done for high-risk customers?", "corporate-onboarding-kyc"),
            Map.entry("Who is recorded as beneficial owner when no one holds more than 10 percent?", "corporate-onboarding-kyc"),
            Map.entry("Compensation for a delayed IMPS reversal", "payment-exceptions"),
            Map.entry("How is a duplicate payment detected?", "payment-exceptions"),
            Map.entry("How quickly are complaints resolved?", "payment-exceptions"));

    private static HybridIndex index;

    @BeforeAll
    static void load() {
        index = TestSupport.corpusIndex();
    }

    @Test
    void retrievalMeetsTheBar() {
        int hit1 = 0;
        int hit3 = 0;
        for (var g : GOLDEN.entrySet()) {
            List<String> docs = index.search(g.getKey(), 3).stream().map(h -> h.chunk().documentId()).toList();
            if (!docs.isEmpty() && docs.get(0).equals(g.getValue())) {
                hit1++;
            }
            if (docs.contains(g.getValue())) {
                hit3++;
            } else {
                System.out.println("MISS: " + g.getKey() + " -> " + docs);
            }
        }
        double at1 = hit1 / (double) GOLDEN.size();
        double at3 = hit3 / (double) GOLDEN.size();
        System.out.printf("Retrieval eval: hit@1 %.2f, hit@3 %.2f over %d questions%n", at1, at3, GOLDEN.size());
        assertThat(at1).as("hit@1").isGreaterThanOrEqualTo(0.85);
        assertThat(at3).as("hit@3").isEqualTo(1.0);
    }
}
