package com.narayansable.rag.index;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Spells out banking acronyms before text is embedded: "IMPS" becomes "IMPS (Immediate Payment
 * Service)". A general-purpose sentence model has rarely seen NEFT, RTGS or IMPS, so to it they are
 * near-meaningless tokens - a question about "the IMPS limit" then matches any passage about limits.
 * Expanding them on both sides (questions and documents) gives the model words it understands.
 * Lexical search still sees the original text.
 */
public class GlossaryEmbedder implements Embedder {

    static final Map<String, String> GLOSSARY = Map.ofEntries(
            Map.entry("NEFT", "National Electronic Funds Transfer"),
            Map.entry("RTGS", "Real Time Gross Settlement high-value payment"),
            Map.entry("IMPS", "Immediate Payment Service instant transfer"),
            Map.entry("UPI", "Unified Payments Interface"),
            Map.entry("KYC", "Know Your Customer identity verification"),
            Map.entry("IFSC", "Indian Financial System Code bank branch code"),
            Map.entry("PAN", "Permanent Account Number tax identifier"),
            Map.entry("OVD", "officially valid document"),
            Map.entry("RM", "Relationship Manager"),
            Map.entry("EOD", "end of day"),
            Map.entry("ZBA", "zero balance account"));
    // Payment-scheme acronyms are unambiguous in any case ("imps"); short ones that are also words
    // in lower case ("pan", "rm") only match in capitals.
    private static final Pattern ACRONYM = Pattern.compile(
            "\\b((?i:NEFT|RTGS|IMPS|UPI|KYC|IFSC|EOD|ZBA)|PAN|OVD|RM)\\b");

    private final Embedder delegate;

    public GlossaryEmbedder(Embedder delegate) {
        this.delegate = delegate;
    }

    static String expand(String text) {
        Matcher m = ACRONYM.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String acronym = m.group(1);
            String meaning = GLOSSARY.get(acronym.toUpperCase(Locale.ROOT));
            m.appendReplacement(out, Matcher.quoteReplacement(acronym + " (" + meaning + ")"));
        }
        m.appendTail(out);
        return out.toString();
    }

    @Override
    public int dimension() {
        return delegate.dimension();
    }

    @Override
    public float[] embed(String text) {
        return delegate.embed(expand(text));
    }

    @Override
    public List<float[]> embedAll(List<String> texts) {
        return delegate.embedAll(texts.stream().map(GlossaryEmbedder::expand).toList());
    }
}
