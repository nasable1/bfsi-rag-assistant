package com.narayansable.rag.answer;

import com.narayansable.rag.llm.LlmClient;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Turns a follow-up ("and above Rs 10 crore?") into a standalone question, so retrieval, the model
 * and the audit trail all see what was actually asked.
 *
 * <p>The server is stateless: the client sends the earlier turns with each question. They are
 * client-supplied text, so they are used only to rewrite the question - never shown to the model as
 * its own past answers - and the rewritten question goes through the injection check again.
 *
 * <p>The model rewrites when it can. Offline (or if that call fails) a follow-up is detected by
 * its shape - short, starting with "and"/"what about", or leaning on "it"/"that" - and is searched
 * together with the previous question.
 */
final class QueryRewriter {

    private static final Pattern FOLLOW_UP_START = Pattern.compile(
            "^(and|also|but|or|what about|how about|what if|same|then|in that case|if so)\\b");
    private static final Set<String> REFERENCES = Set.of("it", "its", "that", "this", "those", "these", "they",
            "them", "there", "such", "same", "former", "latter");

    private QueryRewriter() {
    }

    static String rewrite(String question, List<LlmClient.Turn> history, LlmClient llm) {
        if (history.isEmpty()) {
            return question;
        }
        return llm.rewrite(question, history)
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .orElseGet(() -> heuristic(question, history));
    }

    static String heuristic(String question, List<LlmClient.Turn> history) {
        if (!looksLikeFollowUp(question)) {
            return question;
        }
        String previous = history.get(history.size() - 1).question().strip();
        return previous + " " + question.strip();
    }

    static boolean looksLikeFollowUp(String question) {
        String q = question.toLowerCase(Locale.ROOT).strip();
        if (FOLLOW_UP_START.matcher(q).find()) {
            return true;
        }
        String[] words = q.split("[^\\p{L}\\p{N}]+");
        for (String w : words) {
            if (REFERENCES.contains(w)) {
                return true;
            }
        }
        return words.length <= 3;
    }
}
