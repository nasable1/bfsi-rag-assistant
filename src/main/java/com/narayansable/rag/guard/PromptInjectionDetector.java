package com.narayansable.rag.guard;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Rejects questions that try to change the assistant's instructions instead of asking about the
 * documents. Pattern-based on purpose: it is cheap, deterministic and auditable, and it runs before
 * anything reaches a model. It is the first layer, not the only one - the system prompt also tells
 * the model to treat retrieved text as data, and answers must cite the supplied sources.
 */
@Component
public class PromptInjectionDetector {

    private static final List<Pattern> PATTERNS = List.of(
            Pattern.compile("ignore (all |any )?(the )?(previous|prior|above|earlier) (instructions|rules|prompts?)"),
            Pattern.compile("disregard (the |your )?(instructions|rules|system prompt|guidelines)"),
            Pattern.compile("(reveal|show|print|repeat|leak) (me )?(the |your )?(system prompt|instructions|hidden prompt)"),
            Pattern.compile("\\byou are now\\b|\\bact as (an? )?(unrestricted|jailbroken|different)"),
            Pattern.compile("\\b(developer|dan|jailbreak) mode\\b"),
            Pattern.compile("pretend (that )?(you|the rules) (are|have) no (rules|restrictions|limits)"),
            Pattern.compile("</?(system|assistant|instructions?)>"),
            Pattern.compile("(transfer|move|send) (money|funds|rs|inr|₹).{0,40}(to|into) (account|a/c)"));

    /** The matched phrase when the text looks like an injection attempt. */
    public Optional<String> detect(String text) {
        String t = text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        for (Pattern p : PATTERNS) {
            var m = p.matcher(t);
            if (m.find()) {
                return Optional.of(m.group());
            }
        }
        return Optional.empty();
    }
}
