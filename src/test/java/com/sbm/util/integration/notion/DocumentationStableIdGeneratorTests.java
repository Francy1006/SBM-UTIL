package com.sbm.util.integration.notion;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentationStableIdGeneratorTests {

    private final DocumentationStableIdGenerator generator =
            new DocumentationStableIdGenerator();

    @Test
    void sameInputProducesSameStableId() {
        String first = generator.generate("SBM-UTIL", "docs/architecture.md");

        assertThat(first).isEqualTo(generator.generate("SBM-UTIL", "docs/architecture.md"));
    }

    @Test
    void externalSpacesDoNotChangeStableId() {
        String expected = generator.generate("SBM-UTIL", "docs/architecture.md");

        assertThat(generator.generate(" SBM-UTIL ", " docs/architecture.md "))
                .isEqualTo(expected);
    }

    @Test
    void differentProjectProducesDifferentStableId() {
        assertThat(generator.generate("SBM-UTIL", "docs/architecture.md"))
                .isNotEqualTo(generator.generate("SBM-CORE", "docs/architecture.md"));
    }

    @Test
    void differentSourcePathProducesDifferentStableId() {
        assertThat(generator.generate("SBM-UTIL", "docs/architecture.md"))
                .isNotEqualTo(generator.generate("SBM-UTIL", "docs/security.md"));
    }

    @Test
    void resultIsLowercaseHexadecimalWith64Characters() {
        String stableId = generator.generate("SBM-UTIL", "docs/architecture.md");

        assertThat(stableId)
                .hasSize(64)
                .matches("[0-9a-f]{64}");
    }
}