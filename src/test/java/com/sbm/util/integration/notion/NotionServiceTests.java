package com.sbm.util.integration.notion;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotionServiceTests {

    @Test
    void retrievesConfiguredRootPage() {
        NotionClient notionClient = mock(NotionClient.class);
        NotionProperties properties = new NotionProperties("", "", "root-123");
        String responseBody = "{\"object\":\"page\",\"id\":\"root-123\"}";
        when(notionClient.retrievePage("root-123")).thenReturn(responseBody);
        NotionService notionService = new NotionService(notionClient, properties);

        String result = notionService.retrieveRootPage();

        verify(notionClient).retrievePage("root-123");
        assertThat(result).isEqualTo(responseBody);
    }

    @Test
    void createsChildPageThroughNotionClient() {
        NotionClient notionClient = mock(NotionClient.class);
        NotionService notionService = new NotionService(
                notionClient,
                new NotionProperties("", "", "root-123")
        );
        String parentPageId = "parent-123";
        String title = "Child page";
        String childrenJson = "[{\"object\":\"block\",\"type\":\"paragraph\"}]";
        String responseBody = "{\"object\":\"page\",\"id\":\"created-page-123\"}";
        when(notionClient.createChildPage(parentPageId, title, childrenJson)).thenReturn(responseBody);

        String result = notionService.createChildPage(parentPageId, title, childrenJson);

        verify(notionClient).createChildPage(parentPageId, title, childrenJson);
        assertThat(result).isEqualTo(responseBody);
    }

    @Test
    void delegatesBlockOperationsToNotionClient() {
        NotionClient notionClient = mock(NotionClient.class);
        NotionService notionService = new NotionService(
                notionClient, new NotionProperties("", "", "root-123"));
        when(notionClient.retrieveBlockChildren("page-123")).thenReturn("{\"results\":[]}");
        when(notionClient.updatePageTitle("page-123", "Title")).thenReturn("updated");
        when(notionClient.appendBlockChildren("page-123", "[]")).thenReturn("appended");

        assertThat(notionService.retrieveBlockChildren("page-123")).isEqualTo("{\"results\":[]}");
        assertThat(notionService.updatePageTitle("page-123", "Title")).isEqualTo("updated");
        notionService.deleteBlock("block-123");
        assertThat(notionService.appendBlockChildren("page-123", "[]")).isEqualTo("appended");

        verify(notionClient).retrieveBlockChildren("page-123");
        verify(notionClient).updatePageTitle("page-123", "Title");
        verify(notionClient).deleteBlock("block-123");
        verify(notionClient).appendBlockChildren("page-123", "[]");
    }
}