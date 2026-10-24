package com.narayansable.rag.index;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * What a caller may retrieve. A document with no access groups is open to every caller; a document
 * with groups is visible only to callers in at least one of them. {@code everything} (ADMIN) sees
 * all documents.
 *
 * <p>The filter runs inside retrieval, before ranking and before anything reaches the model, so a
 * restricted document can neither be cited nor shape an answer - and "not in the documents" reads
 * the same whether the answer is absent or hidden, so its existence does not leak.
 */
public record Access(boolean everything, Set<String> groups) {

    public static final Pattern GROUP = Pattern.compile("[a-z0-9][a-z0-9-]{0,39}");
    public static final int MAX_GROUPS = 10;
    public static final Access ALL = new Access(true, Set.of());
    public static final Access PUBLIC = new Access(false, Set.of());

    public Access {
        groups = Set.copyOf(groups == null ? Set.of() : groups);
    }

    public static Access of(Collection<String> groups) {
        return new Access(false, normalise(groups));
    }

    public boolean canSee(Set<String> documentGroups) {
        if (everything || documentGroups == null || documentGroups.isEmpty()) {
            return true;
        }
        for (String g : documentGroups) {
            if (groups.contains(g)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Lower-cased, de-duplicated and validated group names, sorted. Throws on a malformed name or
     * too many groups; null or blank entries are ignored.
     */
    public static Set<String> normalise(Collection<String> raw) {
        Set<String> out = new TreeSet<>();
        for (String g : raw == null ? List.<String>of() : raw) {
            if (g == null || g.isBlank()) {
                continue;
            }
            String n = g.strip().toLowerCase(Locale.ROOT);
            if (!GROUP.matcher(n).matches()) {
                throw new IllegalArgumentException("Access group '" + g.strip()
                        + "' must be 1-40 characters: lower-case letters, digits and hyphens");
            }
            out.add(n);
        }
        if (out.size() > MAX_GROUPS) {
            throw new IllegalArgumentException("At most " + MAX_GROUPS + " access groups per document or key");
        }
        return out;
    }

    /** "treasury, ops" -> {ops, treasury}. */
    public static Set<String> parse(String commaSeparated) {
        return normalise(commaSeparated == null ? List.of() : List.of(commaSeparated.split(",")));
    }
}
