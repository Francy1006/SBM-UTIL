package com.sbm.util.integration.notion;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentationPagePreparationServiceTests {

    @Test
    void preparesDocumentationPageThroughFactory() {
        DocumentationPageFactory factory = mock(DocumentationPageFactory.class);
        String project = "SBM-UTIL";
        String title = "Architecture";
        String markdown = "# Architecture";
        String sourcePath = "docs/architecture.md";
        DocumentationPage expected = new DocumentationPage(
                "stable-id-123",
                project,
                title,
                markdown,
                sourcePath
        );
        when(factory.create(project, title, markdown, sourcePath)).thenReturn(expected);
        DocumentationPagePreparationService service =
                new DocumentationPagePreparationService(factory);

        DocumentationPage result = service.prepare(project, title, markdown, sourcePath);

        verify(factory).create(project, title, markdown, sourcePath);
        assertThat(result).isSameAs(expected);
    }
}