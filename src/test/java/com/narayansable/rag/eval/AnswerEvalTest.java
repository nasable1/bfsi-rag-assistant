package com.narayansable.rag.eval;

import com.narayansable.rag.TestSupport;
import com.narayansable.rag.llm.ExtractiveClient;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Answer-quality gate, offline: the full pipeline (guardrails, hybrid retrieval, extractive
 * answers, citation check) over the golden set. The build fails if any score drops below the bar,
 * so a change that makes answers worse - or makes the assistant answer what it should refuse -
 * cannot ship. The report lands in target/eval/.
 */
class AnswerEvalTest {

    @Test
    void extractiveAnswersMeetTheBar() {
        var report = AnswerEvaluator.run(TestSupport.pipeline(new ExtractiveClient()), "extractive");
        report.write(Path.of("target", "eval", "answer-eval-extractive.md"));
        System.out.println(report.summary());
        // Currently 1.00 on all four. A little headroom on the first three; none on refusals - answering
        // what the documents do not cover is the failure a bank cannot accept.
        assertThat(report.answered()).as("answered").isGreaterThanOrEqualTo(0.95);
        assertThat(report.factAccuracy()).as("fact accuracy").isGreaterThanOrEqualTo(0.90);
        assertThat(report.citationAccuracy()).as("citation accuracy").isGreaterThanOrEqualTo(0.95);
        assertThat(report.refusalAccuracy()).as("refusal accuracy").isEqualTo(1.0);
    }
}
