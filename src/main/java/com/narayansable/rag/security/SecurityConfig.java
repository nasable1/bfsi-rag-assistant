package com.narayansable.rag.security;

import com.narayansable.rag.config.RagProperties;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

/**
 * Who may do what. Callers authenticate with an API key ({@link ApiKeyAuthFilter}); there are no
 * sessions or cookies, so CSRF protection has nothing to protect and is off.
 *
 * <ul>
 *   <li>Public: the web UI's static files and the health endpoint (for load balancers).</li>
 *   <li>USER: ask questions, list documents, read stats.</li>
 *   <li>ADMIN: also upload and delete documents, read and verify the audit trail, and the other
 *       Actuator endpoints (metrics, Prometheus).</li>
 * </ul>
 */
@Configuration
public class SecurityConfig {

    static final String CSP = "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; "
            + "connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'";

    @Bean
    ApiKeyRegistry apiKeyRegistry(RagProperties props) {
        return new ApiKeyRegistry(props.security());
    }

    @Bean
    SecurityFilterChain api(HttpSecurity http, ApiKeyRegistry registry) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(new ApiKeyAuthFilter(registry), AnonymousAuthenticationFilter.class)
                .authorizeHttpRequests(a -> a
                        // Streaming responses complete on an async dispatch; the original request was checked.
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.GET, "/", "/index.html", "/app.js", "/app.css", "/favicon.svg")
                        .permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/actuator/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/ask", "/api/ask/stream").hasAnyRole("USER", "ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/me", "/api/stats", "/api/documents")
                        .hasAnyRole("USER", "ADMIN")
                        .requestMatchers("/api/documents", "/api/documents/**", "/api/audit/**").hasRole("ADMIN")
                        .anyRequest().denyAll())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> SecurityResponses.write(res,
                                HttpServletResponse.SC_UNAUTHORIZED, "API key required (X-API-Key header)"))
                        .accessDeniedHandler((req, res, ex) -> SecurityResponses.write(res,
                                HttpServletResponse.SC_FORBIDDEN, "This API key may not do that")))
                .headers(h -> h
                        .contentSecurityPolicy(c -> c.policyDirectives(CSP))
                        .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)));
        return http.build();
    }
}
