package com.narayansable.rag.guard;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Masks Indian banking PII (email, UPI ID, PAN, card, Aadhaar, IFSC, phone, account number) before text leaves the service (to an LLM) or is written to the audit
 * trail. A customer pasting "my account 001234567890, PAN ABCDE1234F" into a question must not
 * send either to a third-party model.
 *
 * <p>Order matters: the most specific patterns run first - cards before Aadhaar (a card's first 12
 * digits look like one), and both before the generic account rule. Card numbers must pass the Luhn
 * check, which keeps reference numbers and amounts from being masked as cards.
 */
@Component
public class PiiRedactor {

    private static final Map<String, Pattern> RULES = new LinkedHashMap<>();

    static {
        RULES.put("EMAIL", Pattern.compile("[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+"));
        // UPI IDs (name@okbank) have no dot after the @, so the email rule does not catch them.
        RULES.put("UPI", Pattern.compile("[\\w.-]{2,}@[A-Za-z][A-Za-z0-9]{1,63}\\b"));
        // PAN and IFSC are matched case-insensitively: people type them in lower case too.
        RULES.put("PAN", Pattern.compile("\\b[A-Z]{5}\\d{4}[A-Z]\\b", Pattern.CASE_INSENSITIVE));
        // Cards before Aadhaar: the first 12 digits of "4111 1111 1111 1111" look like an Aadhaar.
        RULES.put("CARD", Pattern.compile("\\b(?:\\d[ -]?){12,18}\\d\\b"));
        RULES.put("AADHAAR", Pattern.compile("(?<!\\d)(?<!\\d[ -])[2-9]\\d{3}[ -]?\\d{4}[ -]?\\d{4}(?![ -]?\\d)"));
        RULES.put("IFSC", Pattern.compile("\\b[A-Z]{4}0[A-Z0-9]{6}\\b", Pattern.CASE_INSENSITIVE));
        RULES.put("PHONE", Pattern.compile("(?<!\\d)(?:\\+91[ -]?)?[6-9]\\d{9}(?!\\d)"));
        RULES.put("ACCOUNT", Pattern.compile("(?<!\\d)\\d{9,18}(?!\\d)"));
    }

    public record Result(String text, List<String> found) {
        public boolean redacted() {
            return !found.isEmpty();
        }
    }

    public Result redact(String input) {
        if (input == null || input.isEmpty()) {
            return new Result(input == null ? "" : input, List.of());
        }
        String text = input;
        List<String> found = new ArrayList<>();
        for (var rule : RULES.entrySet()) {
            Matcher m = rule.getValue().matcher(text);
            StringBuilder out = new StringBuilder();
            boolean hit = false;
            while (m.find()) {
                if (rule.getKey().equals("CARD") && !luhn(m.group().replaceAll("[ -]", ""))) {
                    m.appendReplacement(out, Matcher.quoteReplacement(m.group()));
                    continue;
                }
                m.appendReplacement(out, "[" + rule.getKey() + "]");
                hit = true;
            }
            m.appendTail(out);
            if (hit) {
                found.add(rule.getKey());
                text = out.toString();
            }
        }
        return new Result(text, found);
    }

    static boolean luhn(String digits) {
        if (digits.length() < 13) {
            return false;
        }
        int sum = 0;
        boolean dbl = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (dbl) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            dbl = !dbl;
        }
        return sum % 10 == 0;
    }
}
