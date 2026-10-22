package com.narayansable.rag.index;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GlossaryEmbedderTest {

    @Test
    void acronymsAreSpelledOutForTheEmbeddingModel() {
        assertThat(GlossaryEmbedder.expand("IMPS limit for a new beneficiary"))
                .isEqualTo("IMPS (Immediate Payment Service instant transfer) limit for a new beneficiary");
        assertThat(GlossaryEmbedder.expand("what is the rtgs minimum")).contains("rtgs (Real Time Gross Settlement");
    }

    @Test
    void wordsThatOnlyLookLikeAcronymsAreLeftAlone() {
        assertThat(GlossaryEmbedder.expand("a frying pan and an rm -rf command")).isEqualTo("a frying pan and an rm -rf command");
        assertThat(GlossaryEmbedder.expand("PANORAMA of NEFTY")).isEqualTo("PANORAMA of NEFTY");
        assertThat(GlossaryEmbedder.expand("Company PAN")).contains("PAN (Permanent Account Number");
    }
}
