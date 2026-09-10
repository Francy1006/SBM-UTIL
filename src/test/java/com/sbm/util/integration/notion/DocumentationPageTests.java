package com.sbm.util.integration.notion;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentationPageTests {

    @Test
    void preservesAllPageValues() {
        DocumentationPage page = new DocumentationPage(
            "sbm-util-architecture",
                "SBM-UTIL",
                "Architecture",
                "# Architecture",
                "docs/architecture.md"
        );

        assertThat(page.stableId()).isEqualTo("sbm-util-architecture");
        assertThat(page.project()).isEqualTo("SBM-UTIL");
        assertThat(page.title()).isEqualTo("Architecture");
        assertThat(page.markdown()).isEqualTo("# Architecture");
        assertThat(page.sourcePath()).isEqualTo("docs/architecture.md");
    }
}