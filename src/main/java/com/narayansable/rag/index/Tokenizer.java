package com.narayansable.rag.index;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Lowercase word tokens with stop words removed and a light suffix stemmer. Banking acronyms
 * (NEFT, RTGS, IFSC) survive untouched; "cut-off" and "cutoff" meet as "cutoff".
 */
public final class Tokenizer {

    private static final Set<String> STOP = Set.of(
            "a", "an", "the", "and", "or", "of", "to", "in", "on", "for", "at", "by", "is", "are", "was", "be", "been",
            "it", "its", "this", "that", "with", "as", "from", "can", "will", "do", "does", "what", "which", "who", "how",
            "when", "i", "we", "you", "our", "your", "my", "me", "if", "any", "all", "per", "than", "then", "there",
            "their", "they", "them", "these", "those", "so", "such", "not", "no", "may", "must", "should", "would");

    private Tokenizer() {
    }

    public static List<String> tokens(String text) {
        String t = text.toLowerCase(Locale.ROOT).replaceAll("(\\p{L})-(\\p{L})", "$1$2");   // cut-off -> cutoff
        List<String> out = new ArrayList<>();
        for (String w : t.split("[^\\p{L}\\p{N}]+")) {
            if (w.length() < 2 || STOP.contains(w)) {
                continue;
            }
            out.add(stem(w));
        }
        return out;
    }

    static String stem(String w) {
        if (w.length() <= 4 || w.chars().allMatch(Character::isDigit)) {
            return w;
        }
        if (w.endsWith("ies")) {
            return w.substring(0, w.length() - 3) + "y";
        }
        if (w.endsWith("ing") && w.length() > 6) {
            return w.substring(0, w.length() - 3);
        }
        if (w.endsWith("ed") && w.length() > 5) {
            return w.substring(0, w.length() - 2);
        }
        if (w.endsWith("es") && w.length() > 5 && !w.endsWith("ses")) {
            return w.substring(0, w.length() - 2);
        }
        if (w.endsWith("s") && !w.endsWith("ss")) {
            return w.substring(0, w.length() - 1);
        }
        return w;
    }
}
