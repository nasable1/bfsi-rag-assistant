package com.narayansable.rag.web;

import com.narayansable.rag.answer.Answer;
import com.narayansable.rag.answer.AnswerService;
import com.narayansable.rag.audit.AuditTrail;
import com.narayansable.rag.index.HybridIndex;
import com.narayansable.rag.ingest.CorpusLoader;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class AskController {

    public record AskRequest(@NotBlank @Size(max = 1000) String question) {
    }

    public record IngestRequest(@NotBlank @Pattern(regexp = "[a-z0-9-]{2,60}") String id,
                                @NotBlank @Size(max = 200_000) String markdown) {
    }

    private final AnswerService answers;
    private final CorpusLoader loader;
    private final HybridIndex index;
    private final AuditTrail audit;

    public AskController(AnswerService answers, CorpusLoader loader, HybridIndex index, AuditTrail audit) {
        this.answers = answers;
        this.loader = loader;
        this.index = index;
        this.audit = audit;
    }

    @PostMapping("/ask")
    public Answer ask(@Valid @RequestBody AskRequest request) {
        return answers.ask(request.question());
    }

    @PostMapping("/documents")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> ingest(@Valid @RequestBody IngestRequest request) {
        return Map.of("id", request.id(), "chunks", loader.ingest(request.id(), request.markdown()));
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        long broken = audit.verify();
        return Map.of("documents", index.documentCount(), "chunks", index.size(),
                "auditEntries", audit.size(), "auditChainIntact", broken < 0);
    }
}
