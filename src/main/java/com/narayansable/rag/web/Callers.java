package com.narayansable.rag.web;

import com.narayansable.rag.index.Access;
import com.narayansable.rag.security.ApiKeyAuthFilter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

import java.util.Set;
import java.util.TreeSet;

/** What the authenticated caller may retrieve, from the authorities the API-key filter set. */
final class Callers {

    private Callers() {
    }

    static boolean isAdmin(Authentication auth) {
        return auth != null && auth.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }

    static Set<String> groups(Authentication auth) {
        Set<String> out = new TreeSet<>();
        if (auth != null) {
            for (GrantedAuthority a : auth.getAuthorities()) {
                if (a.getAuthority().startsWith(ApiKeyAuthFilter.GROUP_PREFIX)) {
                    out.add(a.getAuthority().substring(ApiKeyAuthFilter.GROUP_PREFIX.length()));
                }
            }
        }
        return out;
    }

    static Access access(Authentication auth) {
        return isAdmin(auth) ? Access.ALL : new Access(false, groups(auth));
    }
}
