package com.narayansable.rag.web;

import com.narayansable.rag.answer.Answer;
import com.narayansable.rag.answer.AnswerService;
import com.narayansable.rag.audit.AuditTrail;
import com.narayansable.rag.config.RagProperties;
import com.narayansable.rag.index.Retriever;
import com.narayansable.rag.llm.LlmClient;
import com.narayansable.rag.security.RateLimiter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class AskController {

    public record TurnDto(@NotNull @Size(max = 4000) String question, @NotNull @Size(max = 4000) String answer) {
    }

    /** {@code history}: earlier turns of the conversation, oldest first, for follow-up questions. */
    public record AskRequest(@NotBlank @Size(max = 1000) String question, @Size(max = 10) List<@Valid TurnDto> history) {

        AnswerService.Question toQuestion() {
            List<LlmClient.Turn> turns = history == null ? List.of()
                    : history.stream().map(t -> new LlmClient.Turn(t.question(), t.answer())).toList();
            return new AnswerService.Question(question, turns);
        }
    }

    private static final Logger log = LoggerFactory.getLogger(AskController.class);
    private static final long STREAM_TIMEOUT_MS = 180_000;

    private final AnswerService answers;
    private final Retriever retriever;
    private final AuditTrail audit;
    private final LlmClient llm;
    private final ThreadPoolTaskExecutor executor;
    private final RateLimiter limiter;
    private final AuditStatus auditStatus;

    public AskController(AnswerService answers, Retriever retriever, AuditTrail audit, LlmClient llm,
                         ThreadPoolTaskExecutor askExecutor, RagProperties props) {
        this.answers = answers;
        this.retriever = retriever;
        this.audit = audit;
        this.llm = llm;
        this.executor = askExecutor;
        this.limiter = new RateLimiter(props.security().asksPerMinute());
        this.auditStatus = new AuditStatus(audit);
    }

    @PostMapping("/ask")
    public Answer ask(@Valid @RequestBody AskRequest request, Authentication auth) {
        throttle(auth);
        return answers.ask(request.toQuestion(), auth.getName(), delta -> {
        });
    }

    /**
     * The same answer as Server-Sent Events: {@code delta} events carry draft text as it is
     * generated, then one {@code answer} event carries the checked answer, which replaces the draft
     * (citations may have been repaired, or the answer withheld). {@code error} ends a failed stream.
     */
    @PostMapping(value = "/ask/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@Valid @RequestBody AskRequest request, Authentication auth) {
        throttle(auth);
        String user = auth.getName();
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        var question = request.toQuestion();
        try {
            executor.execute(() -> run(emitter, question, user));
        } catch (TaskRejectedException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Too many answers in progress - retry shortly");
        }
        return emitter;
    }

    private void run(SseEmitter emitter, AnswerService.Question question, String user) {
        // If the client goes away the answer still completes and is audited; sending just stops.
        boolean[] gone = {false};
        try {
            Answer a = answers.ask(question, user, delta -> {
                if (!gone[0]) {
                    gone[0] = !send(emitter, "delta", Map.of("text", delta));
                }
            });
            if (!gone[0] && send(emitter, "answer", a)) {
                emitter.complete();
            }
        } catch (RuntimeException e) {
            log.error("Streaming answer failed", e);
            if (!gone[0] && send(emitter, "error", Map.of("error", "The answer could not be produced"))) {
                emitter.complete();
            }
        }
    }

    private static boolean send(SseEmitter emitter, String name, Object data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON));
            return true;
        } catch (IOException | IllegalStateException e) {
            return false;                       // client disconnected or the stream timed out
        }
    }

    private void throttle(Authentication auth) {
        if (!limiter.tryAcquire(auth.getName())) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many questions - try again in a minute");
        }
    }

    @GetMapping("/me")
    public Map<String, Object> me(Authentication auth) {
        String role = auth.getAuthorities().stream().map(a -> a.getAuthority().replace("ROLE_", "")).findFirst()
                .orElse("USER");
        return Map.of("name", auth.getName(), "role", role);
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        var v = auditStatus.get();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("documents", retriever.documentCount());
        out.put("chunks", retriever.size());
        out.put("semanticSearch", retriever.semanticEnabled());
        out.put("model", llm.name());
        out.put("auditEntries", audit.size());
        out.put("auditChainIntact", v.intact());
        return out;
    }

    /**
     * Full chain verification reads every entry, so /stats reuses a result for up to 30 seconds
     * (the UI refreshes stats after every answer). /api/audit/verify always checks afresh.
     */
    static final class AuditStatus {
        private static final long TTL_NANOS = 30_000_000_000L;
        private final AuditTrail audit;
        private AuditTrail.Verification last;
        private long lastAt;

        AuditStatus(AuditTrail audit) {
            this.audit = audit;
        }

        synchronized AuditTrail.Verification get() {
            long now = System.nanoTime();
            if (last == null || now - lastAt > TTL_NANOS) {
                last = audit.verify();
                lastAt = now;
            }
            return last;
        }
    }
}
