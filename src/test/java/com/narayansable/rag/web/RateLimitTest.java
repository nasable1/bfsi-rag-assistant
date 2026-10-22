package com.narayansable.rag.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "rag.security.asks-per-minute=2")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RateLimitTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void eachCallerIsLimitedOnBothAskEndpoints() throws Exception {
        String body = "{\"question\":\"When do end-of-day sweeps run?\"}";
        mvc.perform(post("/api/ask").header("X-API-Key", AskControllerTest.USER)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        mvc.perform(post("/api/ask").header("X-API-Key", AskControllerTest.USER)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        mvc.perform(post("/api/ask").header("X-API-Key", AskControllerTest.USER)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isTooManyRequests())
                .andExpect(content().string(containsString("Too many questions")));
        mvc.perform(post("/api/ask/stream").header("X-API-Key", AskControllerTest.USER)
                        .contentType(MediaType.APPLICATION_JSON).accept(MediaType.TEXT_EVENT_STREAM).content(body))
                .andExpect(status().isTooManyRequests());
        // Another caller has their own allowance.
        mvc.perform(post("/api/ask").header("X-API-Key", AskControllerTest.ADMIN)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
    }
}
