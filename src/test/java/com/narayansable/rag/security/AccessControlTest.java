package com.narayansable.rag.security;

import com.narayansable.rag.TestSupport;
import com.narayansable.rag.config.RagProperties;
import com.narayansable.rag.guard.PiiRedactor;
import com.narayansable.rag.index.Access;
import com.narayansable.rag.index.LexicalIndex;
import com.narayansable.rag.index.Retriever;
import com.narayansable.rag.ingest.Chunk;
import com.narayansable.rag.ingest.DocumentParser;
import com.narayansable.rag.ingest.DocumentRepository;
import com.narayansable.rag.ingest.DocumentService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Entitlement-filtered retrieval, without a Spring context. */
class AccessControlTest {

    private static Chunk chunk(String doc, String text) {
        return new Chunk(doc + "#0", doc, doc, "", text, 0, 0);
    }

    @Test
    void openDocumentsAreVisibleToEveryoneRestrictedOnlyToTheirGroups() {
        assertThat(Access.PUBLIC.canSee(Set.of())).isTrue();
        assertThat(Access.PUBLIC.canSee(Set.of("treasury"))).isFalse();
        assertThat(Access.of(List.of("Treasury")).canSee(Set.of("treasury", "ops"))).isTrue();
        assertThat(Access.of(List.of("ops")).canSee(Set.of("treasury"))).isFalse();
        assertThat(Access.ALL.canSee(Set.of("treasury"))).isTrue();
    }

    @Test
    void groupNamesAreValidated() {
        assertThat(Access.parse(" Treasury , ops,,treasury ")).containsExactly("ops", "treasury");
        assertThatThrownBy(() -> Access.parse("treasury desk")).hasMessageContaining("lower-case");
        assertThatThrownBy(() -> Access.normalise(List.of("a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k")))
                .hasMessageContaining("At most 10");
    }

    @Test
    void retrievalNeverReturnsAChunkTheCallerMayNotSee() {
        Retriever r = new Retriever(new LexicalIndex());
        r.replaceDocument("limits", List.of(chunk("limits", "The intraday treasury dealing limit is Rs 750 crore.")),
                Set.of("treasury"));
        r.replaceDocument("sweeps", List.of(chunk("sweeps", "End-of-day sweeps run at 7:00 PM; treasury is informed.")));
        assertThat(r.search("treasury dealing limit", 4, Access.PUBLIC)).extracting(h -> h.chunk().documentId())
                .containsExactly("sweeps");
        assertThat(r.search("treasury dealing limit", 4, Access.of(List.of("treasury"))))
                .extracting(h -> h.chunk().documentId()).first().isEqualTo("limits");
        assertThat(r.search("treasury dealing limit", 4)).extracting(h -> h.chunk().documentId()).contains("limits");
        r.removeDocument("limits");
        assertThat(r.accessGroups("limits")).isEmpty();
    }

    @Test
    void groupsSurviveARestartAndFilterTheListing() {
        var db = TestSupport.database();
        var repo = new DocumentRepository(db.jdbc(), db.tx());
        var first = new DocumentService(TestSupport.PROPS, repo, new Retriever(new LexicalIndex()), new DocumentParser(),
                new PiiRedactor());
        var stored = first.ingestMarkdown("treasury-limits", "# Treasury limits\n\nIntraday limit Rs 750 crore.", "admin",
                Set.of("Treasury"));
        assertThat(stored.accessGroups()).containsExactly("treasury");

        Retriever restarted = new Retriever(new LexicalIndex());
        var second = new DocumentService(TestSupport.PROPS, repo, restarted, new DocumentParser(), new PiiRedactor());
        second.start();
        assertThat(restarted.accessGroups("treasury-limits")).containsExactly("treasury");
        assertThat(restarted.accessGroups("liquidity-sweeps")).isEmpty();             // the bundled corpus is open
        assertThat(second.list(Access.PUBLIC)).extracting(DocumentRepository.Summary::id)
                .doesNotContain("treasury-limits").contains("liquidity-sweeps");
        assertThat(second.list(Access.of(List.of("treasury")))).extracting(DocumentRepository.Summary::id)
                .contains("treasury-limits");
        assertThatThrownBy(() -> second.ingestMarkdown("bad", "# X\n\nText.", "admin", Set.of("no spaces")))
                .isInstanceOf(DocumentParser.UnreadableDocumentException.class);
    }

    @Test
    void keysCarryValidatedGroupsAndAdminsSeeEverything() {
        var reg = new ApiKeyRegistry(new RagProperties.Security(List.of(
                new RagProperties.Security.ApiKey("desk", "desk-key-0123456789", "USER", List.of("Treasury")),
                new RagProperties.Security.ApiKey("boss", "boss-key-0123456789", "ADMIN")), 30));
        var desk = reg.lookup("desk-key-0123456789").orElseThrow();
        assertThat(desk.groups()).containsExactly("treasury");
        assertThat(desk.access().canSee(Set.of("treasury"))).isTrue();
        assertThat(reg.lookup("boss-key-0123456789").orElseThrow().access().everything()).isTrue();
        assertThatThrownBy(() -> new ApiKeyRegistry(new RagProperties.Security(List.of(
                new RagProperties.Security.ApiKey("x", "x-key-0123456789ab", "USER", List.of("bad group"))), 30)))
                .hasMessageContaining("API key 'x'");
    }
}
