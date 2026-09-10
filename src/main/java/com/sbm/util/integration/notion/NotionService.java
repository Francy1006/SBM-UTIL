package com.sbm.util.integration.notion;

import org.springframework.stereotype.Service;

@Service
public class NotionService {

    private final NotionClient notionClient;
    private final NotionProperties properties;

    public NotionService(NotionClient notionClient, NotionProperties properties) {
        this.notionClient = notionClient;
        this.properties = properties;
    }

    public String retrieveRootPage() {
        return notionClient.retrievePage(properties.rootPageId());
    }

    public String rootPageId() {
        return properties.rootPageId();
    }

    public String createChildPage(String parentPageId, String title, String childrenJson) {
        return notionClient.createChildPage(parentPageId, title, childrenJson);
    }

    public String retrieveBlockChildren(String pageId) {
        return notionClient.retrieveBlockChildren(pageId);
    }

    public String findChildPageByCreationMarker(String parentPageId, String marker) {
        return notionClient.findChildPageByCreationMarker(parentPageId, marker);
    }

    public void ensurePageParent(String pageId, String parentPageId) {
        notionClient.ensurePageParent(pageId, parentPageId);
    }

    public String updatePageTitle(String pageId, String title) {
        return notionClient.updatePageTitle(pageId, title);
    }

    public void deleteBlock(String blockId) {
        notionClient.deleteBlock(blockId);
    }

    public String appendBlockChildren(String pageId, String childrenJson) {
        return notionClient.appendBlockChildren(pageId, childrenJson);
    }
}