package com.narayansable.rag.audit;

import com.narayansable.rag.TestSupport;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AuditTrailTest {

    private static AuditTrail.Entry add(AuditTrail t, String q, String a) {
        return t.record("alice", q, null, a, List.of("doc / s"), "m", List.of());
    }

    @Test
    void chainIsIntactAndLinked() {
        var t = TestSupport.database().audit();
        var a = add(t, "q1", "line one\nline two");
        var b = t.record("bob", "q2", "q2 standalone", "a2", List.of(), "extractive", List.of("low-confidence"));
        assertThat(b.prevHash()).isEqualTo(a.hash());
        assertThat(t.verify().intact()).isTrue();
        assertThat(t.entry(1)).get().extracting(AuditTrail.Entry::answer).isEqualTo("line one\nline two");
        assertThat(t.entry(2)).get().extracting(AuditTrail.Entry::interpretedAs).isEqualTo("q2 standalone");
    }

    @Test
    void chainContinuesAcrossRestarts() {
        var db = TestSupport.database();
        var first = db.audit();
        add(first, "q1", "a1");
        add(first, "q2", "a2");
        var restarted = db.audit();
        assertThat(add(restarted, "q3", "a3").seq()).isEqualTo(3);
        assertThat(restarted.verify()).isEqualTo(new AuditTrail.Verification(3, -1, null));
    }

    @Test
    void editingAnEntryIsDetected() {
        var db = TestSupport.database();
        var t = db.audit();
        add(t, "q1", "Two checkers");
        add(t, "q2", "a2");
        add(t, "q3", "a3");
        db.jdbc().update("UPDATE audit_log SET answer = 'One checker' WHERE seq = 1");
        var v = t.verify();
        assertThat(v.brokenAt()).isEqualTo(1);
        assertThat(v.reason()).contains("altered");
    }

    @Test
    void deletingAMiddleEntryIsDetected() {
        var db = TestSupport.database();
        var t = db.audit();
        add(t, "q1", "a1");
        add(t, "q2", "a2");
        add(t, "q3", "a3");
        db.jdbc().update("DELETE FROM audit_log WHERE seq = 2");
        assertThat(t.verify().brokenAt()).isEqualTo(2);
    }

    @Test
    void deletingTheNewestEntriesIsDetectedByTheWriterAndByACheckpoint() {
        var db = TestSupport.database();
        var t = db.audit();
        add(t, "q1", "a1");
        add(t, "q2", "a2");
        var checkpoint = t.head();
        add(t, "q3", "a3");
        db.jdbc().update("DELETE FROM audit_log WHERE seq >= 2");

        assertThat(t.verify().brokenAt()).isEqualTo(2);                  // this process wrote up to 3
        var restarted = db.audit();                                      // a fresh process cannot know...
        assertThat(restarted.verify().intact()).isTrue();
        assertThat(restarted.verifyCheckpoint(checkpoint.seq(), checkpoint.hash())).isFalse(); // ...a checkpoint can
    }

    @Test
    void aValidCheckpointIsConfirmed() {
        var t = TestSupport.database().audit();
        add(t, "q1", "a1");
        var head = t.head();
        add(t, "q2", "a2");
        assertThat(t.verifyCheckpoint(head.seq(), head.hash())).isTrue();
        assertThat(t.verifyCheckpoint(head.seq(), "0".repeat(64))).isFalse();
    }

    @Test
    void anUnreadableRowCountsAsBroken() {
        var db = TestSupport.database();
        var t = db.audit();
        add(t, "q1", "a1");
        db.jdbc().update("UPDATE audit_log SET flags = 'not json' WHERE seq = 1");
        assertThat(t.verify().brokenAt()).isEqualTo(1);
    }
}
