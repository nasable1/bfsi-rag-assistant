package com.narayansable.rag.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.narayansable.rag.answer.Answer;
import com.narayansable.rag.answer.AnswerService;
import com.narayansable.rag.llm.LlmClient;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Runs the answer-quality golden set ({@code eval/answer-golden.json}) through the full pipeline
 * and scores it:
 * <ul>
 *   <li><b>answered</b> - share of in-scope questions given a grounded answer;</li>
 *   <li><b>fact accuracy</b> - share of in-scope questions whose answer contains every expected fact;</li>
 *   <li><b>citation accuracy</b> - share of grounded answers that cite the expected document;</li>
 *   <li><b>refusal accuracy</b> - share of out-of-scope questions the assistant declined.</li>
 * </ul>
 * Facts are matched case-insensitively; "a|b" accepts either wording.
 */
public final class AnswerEvaluator {

    public record Case(String id, String question, List<LlmClient.Turn> history, List<String> documents,
                       List<String> facts, boolean expectRefusal, List<String> tags) {
    }

    public record Result(Case c, Answer answer, boolean factsOk, boolean citationOk, boolean refusedOk) {
        boolean pass() {
            return c.expectRefusal() ? refusedOk : factsOk && citationOk;
        }
    }

    public record Report(String model, List<Result> results) {

        private List<Result> inScope() {
            return results.stream().filter(r -> !r.c().expectRefusal()).toList();
        }

        private List<Result> outOfScope() {
            return results.stream().filter(r -> r.c().expectRefusal()).toList();
        }

        public double answered() {
            return share(inScope(), r -> r.answer().grounded());
        }

        public double factAccuracy() {
            return share(inScope(), Result::factsOk);
        }

        public double citationAccuracy() {
            return share(inScope().stream().filter(r -> r.answer().grounded()).toList(), Result::citationOk);
        }

        public double refusalAccuracy() {
            return share(outOfScope(), Result::refusedOk);
        }

        private static double share(List<Result> rs, java.util.function.Predicate<Result> p) {
            return rs.isEmpty() ? 1 : rs.stream().filter(p).count() / (double) rs.size();
        }

        public String summary() {
            return String.format(Locale.ROOT, "Answer eval [%s]: answered %.2f, fact accuracy %.2f, citation accuracy %.2f, "
                            + "refusal accuracy %.2f over %d in-scope + %d out-of-scope questions",
                    model, answered(), factAccuracy(), citationAccuracy(), refusalAccuracy(), inScope().size(),
                    outOfScope().size());
        }

        /** A Markdown report: the summary, then every failure with what was answered. */
        public String markdown() {
            StringBuilder b = new StringBuilder("# Answer evaluation - ").append(model).append("\n\n")
                    .append("| Metric | Score |\n|---|---|\n")
                    .append(row("Answered (in scope)", answered()))
                    .append(row("Fact accuracy", factAccuracy()))
                    .append(row("Citation accuracy", citationAccuracy()))
                    .append(row("Refusal accuracy (out of scope)", refusalAccuracy()))
                    .append("\n## Failures\n\n");
            boolean any = false;
            for (Result r : results) {
                if (r.pass()) {
                    continue;
                }
                any = true;
                b.append("- **").append(r.c().id()).append("** - ").append(r.c().question()).append("\n  - ")
                        .append(r.c().expectRefusal() ? "expected a refusal" : "expected " + r.c().facts()
                                + " citing " + r.c().documents())
                        .append("\n  - got: ").append(r.answer().answer().replace("\n", " "))
                        .append(" (flags ").append(r.answer().flags()).append(")\n");
            }
            if (!any) {
                b.append("None.\n");
            }
            return b.toString();
        }

        private static String row(String name, double v) {
            return String.format(Locale.ROOT, "| %s | %.2f |%n", name, v);
        }

        public void write(Path file) {
            try {
                Files.createDirectories(file.getParent());
                Files.writeString(file, markdown());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    private AnswerEvaluator() {
    }

    public static List<Case> load() {
        try (InputStream in = AnswerEvaluator.class.getResourceAsStream("/eval/answer-golden.json")) {
            JsonNode root = new ObjectMapper().readTree(in);
            List<Case> cases = new ArrayList<>();
            for (JsonNode n : root.get("cases")) {
                List<LlmClient.Turn> history = new ArrayList<>();
                if (n.has("history")) {
                    for (JsonNode t : n.get("history")) {
                        history.add(new LlmClient.Turn(t.get("question").asText(), t.get("answer").asText()));
                    }
                }
                List<String> facts = new ArrayList<>();
                if (n.has("facts")) {
                    n.get("facts").forEach(f -> facts.add(f.asText()));
                }
                List<String> tags = new ArrayList<>();
                if (n.has("tags")) {
                    n.get("tags").forEach(t -> tags.add(t.asText()));
                }
                boolean refuse = "refuse".equals(n.path("expect").asText());
                List<String> docs = n.has("document") ? Arrays.asList(n.get("document").asText().split("\\|")) : List.of();
                cases.add(new Case(n.get("id").asText(), n.get("question").asText(), history, docs, facts, refuse, tags));
            }
            return cases;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static Report run(AnswerService service, String model) {
        List<Result> results = new ArrayList<>();
        for (Case c : load()) {
            Answer a = service.ask(new AnswerService.Question(c.question(), c.history()), "eval", d -> {
            });
            String text = a.answer().toLowerCase(Locale.ROOT);
            boolean facts = a.grounded() && c.facts().stream().allMatch(f -> Arrays.stream(f.split("\\|"))
                    .anyMatch(alt -> text.contains(alt.toLowerCase(Locale.ROOT))));
            boolean cites = a.citations().stream().anyMatch(ct -> c.documents().contains(ct.documentId()));
            results.add(new Result(c, a, facts, cites, !a.grounded()));
        }
        return new Report(model, results);
    }
}
