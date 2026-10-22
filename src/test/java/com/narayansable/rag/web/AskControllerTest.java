package com.narayansable.rag.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "rag.llm.provider=extractive")
class AskControllerTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void answersWithCitations() throws Exception {
        mvc.perform(post("/api/ask").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"When do end-of-day sweeps run?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grounded").value(true))
                .andExpect(jsonPath("$.answer", containsString("7:00 PM")))
                .andExpect(jsonPath("$.citations[0].document", containsString("Liquidity")));
    }

    @Test
    void rejectsBlankQuestion() throws Exception {
        mvc.perform(post("/api/ask").contentType(MediaType.APPLICATION_JSON).content("{\"question\":\" \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void reuploadReplacesTheDocumentAndPiiIsMasked() throws Exception {
        String v1 = "# Demo Bank - Locker Rent\\n\\n## Rent\\n\\nLocker rent is Rs 3,000 a year. Queries: lockers@demobank.in";
        String v2 = "# Demo Bank - Locker Rent\\n\\n## Rent\\n\\nLocker rent is Rs 4,000 a year. Queries: lockers@demobank.in";
        for (String doc : new String[] {v1, v2}) {
            mvc.perform(post("/api/documents").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"id\":\"locker-rent\",\"markdown\":\"" + doc + "\"}"))
                    .andExpect(status().isCreated());
        }
        mvc.perform(post("/api/ask").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"How much is the locker rent per year?\"}"))
                .andExpect(jsonPath("$.answer", containsString("Rs 4,000")))
                .andExpect(jsonPath("$.answer", not(containsString("Rs 3,000"))))
                .andExpect(jsonPath("$.citations[0].excerpt", containsString("[EMAIL]")));
    }

    @Test
    void ingestsADocumentAndAnswersFromIt() throws Exception {
        String doc = "# Demo Bank - Cheque Clearing\\n\\n## Timelines\\n\\nCheques deposited before 2:00 PM are presented "
                + "in the same-day clearing session and credited by the next working day.";
        mvc.perform(post("/api/documents").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"cheque-clearing\",\"markdown\":\"" + doc + "\"}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/ask").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"When are deposited cheques credited?\"}"))
                .andExpect(jsonPath("$.answer", containsString("next working day")));
        mvc.perform(get("/api/stats"))
                .andExpect(jsonPath("$.documents", greaterThan(5)))
                .andExpect(jsonPath("$.auditChainIntact").value(true));
    }
}
