package com.sbm.util.integration.notion;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotionDocumentationSyncServiceTests {

    @Test
    void validatesRootPageThroughNotionService() {
        NotionService notionService = mock(NotionService.class);
        NotionMarkdownBlockMapper notionMarkdownBlockMapper = mock(NotionMarkdownBlockMapper.class);
                DocumentationNotionMappingRepository mappingRepository = mock(DocumentationNotionMappingRepository.class);
                DocumentationMarkdownReader markdownReader = mock(DocumentationMarkdownReader.class);
                DocumentationPagePreparationService pagePreparationService = mock(DocumentationPagePreparationService.class);
        String responseBody = "{\"object\":\"page\",\"id\":\"root-123\"}";
        when(notionService.retrieveRootPage()).thenReturn(responseBody);
        NotionDocumentationSyncService syncService =
                new NotionDocumentationSyncService(notionService, notionMarkdownBlockMapper,
                        mappingRepository, markdownReader, pagePreparationService, new DocumentationPathPolicy(Path.of(System.getProperty("java.io.tmpdir"))));

        String result = syncService.validateRootPage();

        verify(notionService).retrieveRootPage();
        assertThat(result).isEqualTo(responseBody);
    }

    @Test
    void preparesTheSamePageInstanceForSync() {
        NotionService notionService = mock(NotionService.class);
        NotionMarkdownBlockMapper notionMarkdownBlockMapper = mock(NotionMarkdownBlockMapper.class);
        DocumentationNotionMappingRepository mappingRepository = mock(DocumentationNotionMappingRepository.class);
        DocumentationMarkdownReader markdownReader = mock(DocumentationMarkdownReader.class);
        DocumentationPagePreparationService pagePreparationService = mock(DocumentationPagePreparationService.class);
        NotionDocumentationSyncService syncService =
                new NotionDocumentationSyncService(
                        notionService, notionMarkdownBlockMapper, mappingRepository,
                        markdownReader, pagePreparationService
                , new DocumentationPathPolicy(Path.of(System.getProperty("java.io.tmpdir"))));
        DocumentationPage page = new DocumentationPage(
                "stable-id-123",
                "SBM-UTIL",
                "Architecture",
                "# Architecture",
                "docs/architecture.md"
        );

        DocumentationPage result = syncService.prepareForSync(page);

        assertThat(result).isSameAs(page);
    }

    @Test
    void createsPageUsingParentIdAndTitle() {
        NotionService notionService = mock(NotionService.class);
        NotionMarkdownBlockMapper notionMarkdownBlockMapper = mock(NotionMarkdownBlockMapper.class);
        DocumentationNotionMappingRepository mappingRepository = mock(DocumentationNotionMappingRepository.class);
        DocumentationMarkdownReader markdownReader = mock(DocumentationMarkdownReader.class);
        DocumentationPagePreparationService pagePreparationService = mock(DocumentationPagePreparationService.class);
        NotionDocumentationSyncService syncService =
                new NotionDocumentationSyncService(notionService, notionMarkdownBlockMapper,
                        mappingRepository, markdownReader, pagePreparationService, new DocumentationPathPolicy(Path.of(System.getProperty("java.io.tmpdir"))));
        String parentPageId = "parent-123";
        DocumentationPage page = new DocumentationPage(
                "stable-id-123",
                "SBM-UTIL",
                "Architecture",
                "# Architecture",
                "docs/architecture.md"
        );
        String childrenJson = "[{\"object\":\"block\",\"type\":\"paragraph\"}]";
        String responseBody = "{\"object\":\"page\",\"id\":\"created-page-123\"}";
        when(notionMarkdownBlockMapper.toChildrenJson(page.markdown())).thenReturn(childrenJson);
        when(notionService.createChildPage(parentPageId, page.title(), childrenJson))
                .thenReturn(responseBody);

        String result = syncService.createPage(parentPageId, page);

        verify(notionMarkdownBlockMapper).toChildrenJson(page.markdown());
        verify(notionService).createChildPage(parentPageId, page.title(), childrenJson);
        assertThat(result).isEqualTo(responseBody);
    }
}