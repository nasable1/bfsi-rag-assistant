package com.narayansable.rag.audit;

import com.narayansable.rag.config.RagProperties;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AuditTrailTest {

    private AuditTrail trail() {
        return new AuditTrail(new RagProperties("", 700, 100, 4, 0.1,
                new RagProperties.Llm("extractive", "m", "medium", 100, 10), new RagProperties.Audit("")));
    }

    @Test
    void chainIsIntactAndLinked() {
        var t = trail();
        var a = t.record("q1", "a1", List.of("doc"), "extractive", List.of());
        var b = t.record("q2", "a2", List.of(), "extractive", List.of("low-confidence"));
        assertThat(b.prevHash()).isEqualTo(a.hash());
        assertThat(t.verify()).isEqualTo(-1);
    }

    @Test
    void editingAnyPastEntryIsDetected() {
        var t = trail();
        t.record("q1", "a1", List.of(), "m", List.of());
        t.record("q2", "a2", List.of(), "m", List.of());
        t.record("q3", "a3", List.of(), "m", List.of());
        List<AuditTrail.Entry> copy = new ArrayList<>(t.entries());
        var e = copy.get(1);
        copy.set(1, new AuditTrail.Entry(e.seq(), e.at(), e.question(), "a different answer", e.sources(), e.model(),
                e.flags(), e.prevHash(), e.hash()));
        assertThat(AuditTrail.verify(copy)).isEqualTo(2);
    }

    @Test
    void deletingAnEntryIsDetected() {
        var t = trail();
        t.record("q1", "a1", List.of(), "m", List.of());
        t.record("q2", "a2", List.of(), "m", List.of());
        t.record("q3", "a3", List.of(), "m", List.of());
        List<AuditTrail.Entry> copy = new ArrayList<>(t.entries());
        copy.remove(1);
        assertThat(AuditTrail.verify(copy)).isEqualTo(3);
    }
}
