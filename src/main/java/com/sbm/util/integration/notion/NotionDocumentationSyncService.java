package com.sbm.util.integration.notion;

import org.springframework.boot.json.JsonParser;
import org.springframework.boot.json.JsonParserFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import java.util.Comparator;

@Service
public class NotionDocumentationSyncService {

    private final NotionService notionService;
    private final DocumentationPathPolicy pathPolicy;
    private final NotionMarkdownBlockMapper notionMarkdownBlockMapper;
    private final DocumentationNotionMappingRepository mappingRepository;
    private final DocumentationMarkdownReader markdownReader;
    private final DocumentationPagePreparationService pagePreparationService;
    private final JsonParser jsonParser = JsonParserFactory.getJsonParser();
    private static final String PROJECT_KEY = "@project";

    public NotionDocumentationSyncService(
            NotionService notionService,
            NotionMarkdownBlockMapper notionMarkdownBlockMapper,
            DocumentationNotionMappingRepository mappingRepository,
            DocumentationMarkdownReader markdownReader,
            DocumentationPagePreparationService pagePreparationService,
            DocumentationPathPolicy pathPolicy
    ) {
        this.notionService = notionService;
        this.pathPolicy = pathPolicy;
        this.notionMarkdownBlockMapper = notionMarkdownBlockMapper;
        this.mappingRepository = mappingRepository;
        this.markdownReader = markdownReader;
        this.pagePreparationService = pagePreparationService;
    }

    public String validateRootPage() {
        return notionService.retrieveRootPage();
    }

    public DocumentationPage prepareForSync(DocumentationPage page) {
        return page;
    }

    public String createPage(String parentPageId, DocumentationPage page) {
        String childrenJson = notionMarkdownBlockMapper.toChildrenJson(page.markdown());
        return notionService.createChildPage(parentPageId, page.title(), childrenJson);
    }

    public DocumentationSyncResult synchronize(String project, Path documentationPath) {
        Path authorizedPath = pathPolicy.validate(project, documentationPath);
        return mappingRepository.withSyncLock(authorizedPath, () -> synchronizeLocked(project, authorizedPath));
    }

    private DocumentationSyncResult synchronizeLocked(String project, Path documentationPath) {
        DocumentationNotionMapping mapping = mappingRepository.load(documentationPath);
        if (mapping.projectPageId() != null) DocumentationPathPolicy.requireText(mapping.projectPageId(), "projectPageId");
        mapping.documents().forEach((key, value) -> {
            DocumentationPathPolicy.requireText(key, "document stableId");
            DocumentationPathPolicy.requireText(value, "document pageId");
        });
        mapping.directories().forEach((key, value) -> {
            DocumentationPathPolicy.requireText(key, "directory stableId");
            DocumentationPathPolicy.requireText(value, "directory pageId");
        });
        mapping.parents().values().forEach(value -> DocumentationPathPolicy.requireText(value, "parent pageId"));
        if (mapping.projectPageId() == null) {
            String projectId = createOrRecover(notionService.rootPageId(), PROJECT_KEY,
                    documentationPath, mapping);
            mapping.projectPageId(projectId);
            mappingRepository.save(documentationPath, mapping);
        }
        if (mapping.pendingCreates().containsKey(PROJECT_KEY)) {
            notionService.updatePageTitle(mapping.projectPageId(), project);
            mapping.pendingCreates().remove(PROJECT_KEY);
            mappingRepository.save(documentationPath, mapping);
        }

        List<DocumentationMarkdownReader.DocumentationSource> sources =
                markdownReader.discover(project, documentationPath).stream()
                        .sorted(Comparator.comparing(DocumentationMarkdownReader.DocumentationSource::sourcePath))
                        .toList();
        int created = 0;
        int updated = 0;
        int unchanged = 0;
        for (DocumentationMarkdownReader.DocumentationSource source : sources) {
            DocumentationPage page = pagePreparationService.prepare(
                    source.project(), source.title(), source.markdown(), source.sourcePath());
            String parentPageId = ensureDirectories(project, source.sourcePath(), documentationPath, mapping);
            String childrenJson = notionMarkdownBlockMapper.toChildrenJson(page.markdown());
            String hash = contentHash(page.title(), childrenJson);
            String notionPageId = mapping.documents().get(page.stableId());
            boolean newDocument = notionPageId == null;
            if (newDocument) {
                // Old pending creations were made directly under the project root.
                String creationParent = mapping.parents().getOrDefault(page.stableId(),
                        mapping.pendingCreates().containsKey(page.stableId())
                                ? mapping.projectPageId() : parentPageId);
                mapping.parents().put(page.stableId(), creationParent);
                notionPageId = createOrRecover(creationParent, page.stableId(),
                        documentationPath, mapping);
                mapping.documents().put(page.stableId(), notionPageId);
                mappingRepository.save(documentationPath, mapping);
            }
            reconcileDocumentParent(page.stableId(), notionPageId, parentPageId, documentationPath, mapping);
            if (!newDocument && hash.equals(mapping.hashes().get(page.stableId()))) {
                unchanged++;
                continue;
            }

            // A partial replacement no longer represents the previously synced content either.
            // Persist invalidation BEFORE touching Notion, including when the source is reverted.
            mapping.hashes().remove(page.stableId());
            mappingRepository.save(documentationPath, mapping);
            replaceDocument(notionPageId, page, childrenJson);
            mapping.hashes().put(page.stableId(), hash);
            mapping.pendingCreates().remove(page.stableId());
            mappingRepository.save(documentationPath, mapping);
            if (newDocument) {
                created++;
            } else {
                updated++;
            }
        }
        return new DocumentationSyncResult(project, sources.size(), created, updated, unchanged);
    }

    private String ensureDirectories(String project, String sourcePath, Path documentationPath,
                                     DocumentationNotionMapping mapping) {
        String parentId = mapping.projectPageId();
        DocumentationStableIdGenerator ids = new DocumentationStableIdGenerator();
        int separator = sourcePath.indexOf('/');
        while (separator >= 0) {
            String relativeDirectory = sourcePath.substring(0, separator);
            String key = ids.generateDirectory(project, relativeDirectory);
            String pageId = mapping.directories().get(key);
            if (pageId == null) {
                mapping.parents().put(key, parentId);
                pageId = createOrRecover(parentId, key, documentationPath, mapping);
                mapping.directories().put(key, pageId);
                mappingRepository.save(documentationPath, mapping);
            }
            if (mapping.pendingCreates().containsKey(key)) {
                String title = relativeDirectory.substring(relativeDirectory.lastIndexOf('/') + 1);
                notionService.updatePageTitle(pageId, title);
                mapping.pendingCreates().remove(key);
                mappingRepository.save(documentationPath, mapping);
            }
            parentId = pageId;
            separator = sourcePath.indexOf('/', separator + 1);
        }
        return parentId;
    }

    private void reconcileDocumentParent(String stableId, String pageId, String desiredParent,
                                         Path documentationPath, DocumentationNotionMapping mapping) {
        String previousParent = mapping.parents().getOrDefault(stableId, mapping.projectPageId());
        if (!desiredParent.equals(previousParent)) {
            // The page identity is known. A retry checks the remote parent before repeating a move.
            notionService.ensurePageParent(pageId, desiredParent);
        }
        if (!desiredParent.equals(mapping.parents().get(stableId))) {
            mapping.parents().put(stableId, desiredParent);
            mappingRepository.save(documentationPath, mapping);
        }
    }

    private void replaceDocument(String notionPageId, DocumentationPage page, String childrenJson) {
        notionService.updatePageTitle(notionPageId, page.title());
        for (String blockId : extractIds(notionService.retrieveBlockChildren(notionPageId))) {
            notionService.deleteBlock(blockId);
        }
        notionService.appendBlockChildren(notionPageId, childrenJson);
    }

    private String contentHash(String title, String childrenJson) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(title.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            return HexFormat.of().formatHex(digest.digest(childrenJson.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String createOrRecover(String parentPageId, String stableId, Path documentationPath,
                                   DocumentationNotionMapping mapping) {
        DocumentationPathPolicy.requireText(parentPageId, "parentPageId");
        DocumentationPathPolicy.requireText(stableId, "stableId");
        String marker = mapping.pendingCreates().get(stableId);
        if (marker != null) {
            String pageId = notionService.findChildPageByCreationMarker(parentPageId, marker);
            if (pageId == null) {
                // An absent result cannot prove a previous POST did not succeed.
                throw new IllegalStateException("Unresolved Notion creation for " + stableId
                        + " (marker " + marker + "). Retry reconciliation once the page is visible; "
                        + "verify the remote outcome before clearing the pending creation.");
            }
            return pageId;
        }
        marker = "sbm-sync-pending:" + stableId + ":" + UUID.randomUUID();
        mapping.pendingCreates().put(stableId, marker);
        mappingRepository.save(documentationPath, mapping);
        // Do not append chunks or rename the marker until the returned ID is durable.
        return extractId(notionService.createChildPage(parentPageId, marker, "[]"));
    }

    private String extractId(String response) {
        Object id = jsonParser.parseMap(response).get("id");
        if (!(id instanceof String pageId) || pageId.isBlank()) {
            throw new IllegalStateException("Notion response did not contain a page id");
        }
        return pageId;
    }

    private List<String> extractIds(String response) {
        Map<String, Object> payload = jsonParser.parseMap(response);
        Object results = payload.get("results");
        if (!(results instanceof List<?> resultList)) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        for (Object result : resultList) {
            if (result instanceof Map<?, ?> block && block.get("id") instanceof String id) {
                ids.add(id);
            }
        }
        return ids;
    }
}