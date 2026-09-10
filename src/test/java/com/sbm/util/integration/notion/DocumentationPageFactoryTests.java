package com.sbm.util.integration.notion;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentationPageFactoryTests {

    @Test
    void createsDocumentationPageWithGeneratedStableId() {
        DocumentationStableIdGenerator stableIdGenerator =
                mock(DocumentationStableIdGenerator.class);
        String project = "SBM-UTIL";
        String title = "Architecture";
        String markdown = "# Architecture";
        String sourcePath = "docs/architecture.md";
        String stableId = "stable-id-123";
        when(stableIdGenerator.generate(project, sourcePath)).thenReturn(stableId);
        DocumentationPageFactory factory = new DocumentationPageFactory(stableIdGenerator);

        DocumentationPage result = factory.create(project, title, markdown, sourcePath);

        verify(stableIdGenerator).generate(project, sourcePath);
        assertThat(result.stableId()).isEqualTo(stableId);
        assertThat(result.project()).isEqualTo(project);
        assertThat(result.title()).isEqualTo(title);
        assertThat(result.markdown()).isEqualTo(markdown);
        assertThat(result.sourcePath()).isEqualTo(sourcePath);
    }
}