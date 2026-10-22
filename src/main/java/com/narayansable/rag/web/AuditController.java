package com.narayansable.rag.web;

import com.narayansable.rag.audit.AuditTrail;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * The audit trail, for ADMIN callers. {@code head} is what an external system should record as a
 * checkpoint; {@code checkpoint} later proves that entry is still there, unchanged - which also
 * catches deletion of the newest entries, the one edit a hash chain cannot see on its own.
 */
@RestController
@Validated
@RequestMapping("/api/audit")
public class AuditController {

    public record Checkpoint(@Min(1) long seq, @Pattern(regexp = "[0-9a-f]{64}") String hash) {
    }

    private final AuditTrail audit;

    public AuditController(AuditTrail audit) {
        this.audit = audit;
    }

    @GetMapping
    public List<AuditTrail.Entry> recent(@RequestParam(defaultValue = "50") @Min(1) int limit) {
        return audit.recent(Math.min(limit, 500));
    }

    @GetMapping("/{seq}")
    public ResponseEntity<AuditTrail.Entry> entry(@PathVariable long seq) {
        return ResponseEntity.of(audit.entry(seq));
    }

    @GetMapping("/head")
    public AuditTrail.Head head() {
        return audit.head();
    }

    @GetMapping("/verify")
    public AuditTrail.Verification verify() {
        return audit.verify();
    }

    @PostMapping("/checkpoint")
    public Map<String, Object> checkpoint(@Valid @RequestBody Checkpoint c) {
        return Map.of("seq", c.seq(), "valid", audit.verifyCheckpoint(c.seq(), c.hash()));
    }
}
