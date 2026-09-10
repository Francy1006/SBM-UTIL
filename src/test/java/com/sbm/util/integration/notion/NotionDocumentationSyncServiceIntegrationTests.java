package com.sbm.util.integration.notion;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;

class NotionDocumentationSyncServiceIntegrationTests {

    @Test
    void createsProjectAndDocumentThenReusesMappingOnSecondRun() throws Exception {
        Path documentation = Files.createTempDirectory("documentation");
        Files.writeString(documentation.resolve("guide.md"), "# Guide\nBody", StandardCharsets.UTF_8);
        NotionService notionService = mock(NotionService.class);
        NotionMarkdownBlockMapper mapper = mock(NotionMarkdownBlockMapper.class);
        when(notionService.rootPageId()).thenReturn("root-page");
        when(notionService.createChildPage(eq("root-page"), startsWith("sbm-sync-pending:@project:"), eq("[]")))
                .thenReturn("{\"id\":\"project-page\"}");
        when(notionService.createChildPage(eq("project-page"), startsWith("sbm-sync-pending:"), eq("[]")))
                .thenReturn("{\"id\":\"document-page\"}");
        when(mapper.toChildrenJson("# Guide\nBody")).thenReturn("[{\"type\":\"paragraph\"}]");
        NotionDocumentationSyncService service = service(notionService, mapper);

        when(notionService.retrieveBlockChildren("document-page")).thenReturn("{\"results\":[]}");
        DocumentationSyncResult first = service.synchronize("SBM-UTIL", documentation);
        assertThat(first).isEqualTo(new DocumentationSyncResult("SBM-UTIL", 1, 1, 0, 0));
        verify(notionService).createChildPage(eq("root-page"), startsWith("sbm-sync-pending:@project:"), eq("[]"));
        verify(notionService).createChildPage(eq("project-page"), startsWith("sbm-sync-pending:"), eq("[]"));
        verify(notionService).appendBlockChildren("document-page", "[{\"type\":\"paragraph\"}]");
        clearInvocations(notionService);
        DocumentationSyncResult second = service.synchronize("SBM-UTIL", documentation);
        assertThat(second).isEqualTo(new DocumentationSyncResult("SBM-UTIL", 1, 0, 0, 1));
        verify(notionService, never()).createChildPage(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
        verify(notionService, never()).deleteBlock(org.mockito.ArgumentMatchers.anyString());
        verify(notionService, never()).updatePageTitle(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
        verify(notionService, never()).appendBlockChildren(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
        verify(notionService, never()).retrieveBlockChildren(org.mockito.ArgumentMatchers.anyString());
        var repository = new DocumentationNotionMappingRepository();
        String stableId = new DocumentationStableIdGenerator().generate("SBM-UTIL", "guide.md");
        String firstHash = repository.load(documentation).hashes().get(stableId);
        assertThat(firstHash).matches("[0-9a-f]{64}");
        assertThat(Files.readString(documentation.resolve(".sbm/notion-sync-map.json"))).contains(firstHash);
        Files.writeString(documentation.resolve("guide.md"), "# Guide\nChanged");
        when(mapper.toChildrenJson("# Guide\nChanged")).thenReturn("[{\"type\":\"heading_1\"}]");
        DocumentationSyncResult third = service(notionService, mapper).synchronize("SBM-UTIL", documentation);
        assertThat(third).isEqualTo(new DocumentationSyncResult("SBM-UTIL", 1, 0, 1, 0));
        verify(notionService).updatePageTitle("document-page", "Guide");
        verify(notionService).appendBlockChildren("document-page", "[{\"type\":\"heading_1\"}]");
        assertThat(repository.load(documentation).hashes().get(stableId)).isNotEqualTo(firstHash);
        assertThat(repository.load(documentation).documents()).containsEntry(stableId, "document-page");
        assertThat(service(notionService, mapper).synchronize("SBM-UTIL", documentation))
                .isEqualTo(new DocumentationSyncResult("SBM-UTIL", 1, 0, 0, 1));

    }

        @Test
        void replacesExistingDocumentBlocksWithoutCreatingAnotherPage() throws Exception {
                Path documentation = Files.createTempDirectory("documentation");
                Files.writeString(documentation.resolve("guide.md"), "# Guide\nUpdated", StandardCharsets.UTF_8);
                DocumentationStableIdGenerator generator = new DocumentationStableIdGenerator();
                String stableId = generator.generate("SBM-UTIL", "guide.md");
                DocumentationNotionMappingRepository repository = new DocumentationNotionMappingRepository();
                var mapping = new DocumentationNotionMapping("project-page", java.util.Map.of(stableId, "document-page"));
                repository.save(documentation, mapping);
                NotionService notionService = mock(NotionService.class);
                NotionMarkdownBlockMapper mapper = mock(NotionMarkdownBlockMapper.class);
                when(notionService.retrieveBlockChildren("document-page"))
                        .thenReturn("{\"results\":[{\"id\":\"block-1\",\"integration_id\":\"integration-1\"},{\"id\":\"block-2\",\"request_id\":\"request-1\"}],\"integration_id\":\"integration-root\",\"request_id\":\"request-root\"}");
                when(mapper.toChildrenJson("# Guide\nUpdated")).thenReturn("[{\"type\":\"heading_1\"}]");

                DocumentationSyncResult result = new NotionDocumentationSyncService(
                                notionService, mapper, repository, new DocumentationMarkdownReader(),
                                new DocumentationPagePreparationService(new DocumentationPageFactory(generator))
                , new DocumentationPathPolicy(Path.of(System.getProperty("java.io.tmpdir")))).synchronize("SBM-UTIL", documentation);

                assertThat(result).isEqualTo(new DocumentationSyncResult("SBM-UTIL", 1, 0, 1, 0));
                verify(notionService).updatePageTitle("document-page", "Guide");
                verify(notionService).deleteBlock("block-1");
                verify(notionService).deleteBlock("block-2");
                verify(notionService, never()).deleteBlock("integration-1");
                verify(notionService, never()).deleteBlock("request-1");
                verify(notionService, never()).deleteBlock("integration-root");
                verify(notionService, never()).deleteBlock("request-root");
                verify(notionService).appendBlockChildren("document-page", "[{\"type\":\"heading_1\"}]");
                verify(notionService, never()).createChildPage("project-page", "Guide", "[{\"type\":\"heading_1\"}]");
        }

    private NotionDocumentationSyncService service(NotionService notionService, NotionMarkdownBlockMapper mapper) {
        return new NotionDocumentationSyncService(
                notionService,
                mapper,
                new DocumentationNotionMappingRepository(),
                new DocumentationMarkdownReader(),
                new DocumentationPagePreparationService(
                        new DocumentationPageFactory(new DocumentationStableIdGenerator())
                )
        , new DocumentationPathPolicy(Path.of(System.getProperty("java.io.tmpdir"))));
    }
}