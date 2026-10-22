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
 *
 * <p>The patterns pair a verb of overriding ("ignore", "disregard", "forget", "override") with
 * what is being overridden ("previous instructions", "the rules above", "your system prompt"),
 * a few words apart - so "Can I ignore a failed sweep?" is a question, not an attack.
 */
@Component
public class PromptInjectionDetector {

    private static final String OVERRIDE = "(ignore|disregard|forget|override|bypass|skip|drop)";
    private static final String TARGET = "(instructions?|rules|prompts?|guidelines|directives|guardrails|constraints|"
            + "everything|all of that)";
    private static final String POSITION = "(previous|prior|above|earlier|preceding|original|initial|system|your|all)";

    private static final List<Pattern> PATTERNS = List.of(
            // "ignore all previous instructions", "disregard your rules", "forget everything above"
            Pattern.compile(OVERRIDE + "\\b(\\s+\\S+){0,3}?\\s+" + POSITION + "\\b(\\s+\\S+){0,2}?\\s+" + TARGET + "\\b"),
            Pattern.compile(OVERRIDE + "\\s+(the\\s+|your\\s+)?" + TARGET + "\\s+(above|before|so far|you were given)"),
            Pattern.compile(OVERRIDE + "\\s+(everything|all)\\s+(above|before|so far)"),
            Pattern.compile("(reveal|show|print|repeat|leak|output|tell me|what is|what are)\\s+(me\\s+)?(the\\s+|your\\s+)"
                    + "(system prompt|hidden prompt|initial prompt|instructions you were given|system instructions)"),
            Pattern.compile("\\bnew (instructions|rules|system prompt)\\s*:"),
            Pattern.compile("\\byou are now\\b|\\bact as (an? )?(unrestricted|jailbroken|different|unfiltered)"),
            Pattern.compile("\\b(developer|dan|jailbreak|god) mode\\b"),
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
