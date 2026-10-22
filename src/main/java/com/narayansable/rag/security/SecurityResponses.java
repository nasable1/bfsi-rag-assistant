package com.narayansable.rag.security;

import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** JSON error bodies for security failures, in the same shape as the API's other errors. */
final class SecurityResponses {

    private SecurityResponses() {
    }

    static void write(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"status\":" + status + ",\"error\":\"" + message.replace("\"", "'") + "\"}");
    }
}
