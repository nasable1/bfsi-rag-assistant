package com.narayansable.rag.ingest;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChunkerTest {

    @Test
    void chunksKeepTheirSectionHeading() {
        var doc = new Document("d", "Title", "# Title\n\n## NEFT\n\nNEFT runs 24x7.\n\n## RTGS\n\nRTGS minimum is Rs 2,00,000.");
        var chunks = new Chunker(500, 50).split(doc);
        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).section()).isEqualTo("NEFT");
        assertThat(chunks.get(1).section()).isEqualTo("RTGS");
        assertThat(chunks.get(1).text()).contains("Rs 2,00,000");
    }

    @Test
    void longTextIsSplitWithOverlap() {
        String para = "Sentence number one is here. ".repeat(60);
        var chunks = new Chunker(300, 60).split(new Document("d", "T", para));
        assertThat(chunks.size()).isGreaterThan(3);
        assertThat(chunks).allSatisfy(c -> assertThat(c.text().length()).isLessThanOrEqualTo(300 + 60));
        // the start of each later chunk repeats the end of the previous one
        String tail = chunks.get(0).text().substring(chunks.get(0).text().length() - 30);
        assertThat(chunks.get(1).text()).contains(tail.strip().split(" ")[1]);
    }

    @Test
    void overlapMustBeSmallerThanSize() {
        assertThatThrownBy(() -> new Chunker(100, 100)).isInstanceOf(IllegalArgumentException.class);
    }
}
