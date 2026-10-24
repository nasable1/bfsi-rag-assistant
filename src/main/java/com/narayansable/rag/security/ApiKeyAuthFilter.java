package com.narayansable.rag.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Authenticates a request by its API key: {@code X-API-Key: <key>} or {@code Authorization: Bearer
 * <key>} (the form Prometheus can send). A wrong key is rejected here with 401; a missing key
 * continues unauthenticated, and the access rules decide.
 */
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-API-Key";
    public static final String GROUP_PREFIX = "GROUP_";
    private final ApiKeyRegistry registry;

    public ApiKeyAuthFilter(ApiKeyRegistry registry) {
        this.registry = registry;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String key = request.getHeader(HEADER);
        String auth = request.getHeader("Authorization");
        if ((key == null || key.isBlank()) && auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
            key = auth.substring(7);
        }
        if (key == null || key.isBlank()) {
            chain.doFilter(request, response);
            return;
        }
        var caller = registry.lookup(key);
        if (caller.isEmpty()) {
            SecurityResponses.write(response, HttpServletResponse.SC_UNAUTHORIZED, "Invalid API key");
            return;
        }
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_" + caller.get().role()));
        caller.get().groups().forEach(g -> authorities.add(new SimpleGrantedAuthority(GROUP_PREFIX + g)));
        var token = new UsernamePasswordAuthenticationToken(caller.get().name(), null, authorities);
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(token);
        SecurityContextHolder.setContext(context);
        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
