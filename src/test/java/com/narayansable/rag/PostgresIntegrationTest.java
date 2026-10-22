package com.narayansable.rag;

import com.narayansable.rag.index.Embedder;
import com.narayansable.rag.index.PgVectorStore;
import com.narayansable.rag.index.Retriever;
import com.narayansable.rag.ingest.Chunk;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The production database path: Postgres with pgvector, Flyway migrations, embeddings stored and
 * searched in the database, and the append-only audit table. Needs Docker; skipped without it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "postgres"})
@Testcontainers(disabledWithoutDocker = true)
class PostgresIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TransactionTemplate tx;
    @Autowired
    private Retriever retriever;
    @Autowired
    private Embedder embedder;

    @Test
    void answersUsingVectorsStoredInPostgres() throws Exception {
        assertThat(retriever.semanticEnabled()).isTrue();
        Integer vectors = jdbc.queryForObject("SELECT count(*) FROM chunk_vectors", Integer.class);
        assertThat(vectors).isEqualTo(retriever.size());

        mvc.perform(post("/api/ask").header("X-API-Key", "test-user-key-0123456789")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"Can one person both enter and approve a transaction?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grounded").value(true))
                .andExpect(jsonPath("$.citations[0].documentId").value("maker-checker-policy"))
                .andExpect(jsonPath("$.answer", containsString("both create and approve")));
    }

    @Test
    void unchangedChunksAreNotEmbeddedAgain() {
        AtomicInteger embedded = new AtomicInteger();
        Embedder counting = new Embedder() {
            public int dimension() {
                return embedder.dimension();
            }

            public float[] embed(String text) {
                embedded.incrementAndGet();
                return embedder.embed(text);
            }
        };
        var store = new PgVectorStore(jdbc, tx, counting);
        var a = new Chunk("tmp#0", "tmp", "Tmp", "S", "Locker rent is Rs 4,000 a year.", 0, 0);
        var b = new Chunk("tmp#1", "tmp", "Tmp", "S", "Lockers are opened only with two keys.", 1, 0);
        store.replaceDocument("tmp", List.of(a, b));
        assertThat(embedded).hasValue(2);
        store.replaceDocument("tmp", List.of(a, b));                         // a restart: nothing changed
        assertThat(embedded).hasValue(2);
        var a2 = new Chunk("tmp#0", "tmp", "Tmp", "S", "Locker rent is Rs 5,000 a year.", 0, 0);
        store.replaceDocument("tmp", List.of(a2));                           // one changed, one gone
        assertThat(embedded).hasValue(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chunk_vectors WHERE document_id = 'tmp'", Integer.class))
                .isEqualTo(1);
        assertThat(store.search(embedder.embed("How much is the locker rent?"), 1).get(0).chunkId()).isEqualTo("tmp#0");
        store.removeDocument("tmp");
    }

    @Test
    void theAuditTableIsAppendOnly() throws Exception {
        mvc.perform(post("/api/ask").header("X-API-Key", "test-user-key-0123456789")
                .contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"When do end-of-day sweeps run?\"}"));
        assertThatThrownBy(() -> jdbc.update("UPDATE audit_log SET answer = 'edited'"))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM audit_log"))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.execute("TRUNCATE audit_log"))
                .hasMessageContaining("append-only");
    }
}
