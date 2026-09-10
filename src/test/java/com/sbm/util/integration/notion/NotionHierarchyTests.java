package com.sbm.util.integration.notion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NotionHierarchyTests {
    @TempDir Path documentation;
    private final DocumentationNotionMappingRepository repository = new DocumentationNotionMappingRepository();
    private final DocumentationStableIdGenerator ids = new DocumentationStableIdGenerator();
    private final NotionService notion = mock(NotionService.class);
    private final Map<String, String> titles = new LinkedHashMap<>();
    private final Map<String, String> parents = new LinkedHashMap<>();

    private void setup() {
        when(notion.rootPageId()).thenReturn("root");
        when(notion.createChildPage(anyString(), anyString(), eq("[]"))).thenAnswer(call -> {
            String parent = call.getArgument(0);
            assertThat(parent.equals("root") || parent.equals("project-page") || titles.containsKey(parent)).isTrue();
            String marker = call.getArgument(1);
            assertThat(repository.load(documentation).pendingCreates()).containsValue(marker);
            String id = "page-" + titles.size();
            titles.put(id, marker);
            parents.put(id, parent);
            return "{\"id\":\"" + id + "\"}";
        });
        when(notion.updatePageTitle(anyString(), anyString())).thenAnswer(call -> {
            titles.put(call.getArgument(0), call.getArgument(1));
            return "{}";
        });
        when(notion.retrieveBlockChildren(anyString())).thenReturn("{\"results\":[]}");
        when(notion.findChildPageByCreationMarker(anyString(), anyString())).thenAnswer(call ->
                titles.keySet().stream().filter(id -> parents.get(id).equals(call.getArgument(0))
                        && titles.get(id).equals(call.getArgument(1))).findFirst().orElse(null));
        doAnswer(call -> {
            parents.put(call.getArgument(0), call.getArgument(1));
            return null;
        }).when(notion).ensurePageParent(anyString(), anyString());
    }

    private void file(String path) throws Exception {
        Path target = documentation.resolve(path);
        Files.createDirectories(target.getParent());
        Files.writeString(target, "# Same title");
    }

    private NotionDocumentationSyncService service(DocumentationNotionMappingRepository repo,
                                                  DocumentationMarkdownReader reader) {
        return new NotionDocumentationSyncService(notion, new NotionMarkdownBlockMapper(), repo, reader,
                new DocumentationPagePreparationService(new DocumentationPageFactory(ids)), new DocumentationPathPolicy(Path.of(System.getProperty("java.io.tmpdir"))));
    }

    private DocumentationSyncResult sync() {
        return service(repository, new DocumentationMarkdownReader()).synchronize("project", documentation);
    }

    private String doc(String path) { return ids.generate("project", path); }
    private String dir(String path) { return ids.generateDirectory("project", path); }

    @Test
    void buildsNestedTreeReusesContainersAndLeavesEmptyDirectoriesOut() throws Exception {
        setup();
        for (String path : List.of("README.md", "architecture/overview.md",
                "architecture/backend/api.md", "architecture/backend/database.md",
                "other/api.md", "architecture.md")) {
            file(path);
        }
        Files.createDirectories(documentation.resolve("empty/deeper"));
        assertThat(sync()).isEqualTo(new DocumentationSyncResult("project", 6, 6, 0, 0));
        var m = repository.load(documentation);
        assertThat(m.directories()).hasSize(3);
        assertThat(m.documents()).hasSize(6);
        assertThat(parents.get(m.documents().get(doc("README.md")))).isEqualTo(m.projectPageId());
        assertThat(parents.get(m.directories().get(dir("architecture")))).isEqualTo(m.projectPageId());
        assertThat(parents.get(m.directories().get(dir("architecture/backend"))))
                .isEqualTo(m.directories().get(dir("architecture")));
        assertThat(parents.get(m.documents().get(doc("architecture/overview.md"))))
                .isEqualTo(m.directories().get(dir("architecture")));
        for (String name : List.of("api", "database")) {
            assertThat(parents.get(m.documents().get(doc("architecture/backend/" + name + ".md"))))
                    .isEqualTo(m.directories().get(dir("architecture/backend")));
        }
        assertThat(m.documents().get(doc("other/api.md")))
                .isNotEqualTo(m.documents().get(doc("architecture/backend/api.md")));
        assertThat(dir("architecture")).isNotEqualTo(doc("architecture.md"));
        assertThat(m.directories().get(dir("architecture")))
                .isNotEqualTo(m.documents().get(doc("architecture.md")));
        assertThat(sync()).isEqualTo(new DocumentationSyncResult("project", 6, 0, 0, 6));
        verify(notion, times(3)).createChildPage(anyString(), startsWith("sbm-sync-pending:directory:"), eq("[]"));
        verify(notion, times(10)).createChildPage(anyString(), anyString(), eq("[]"));
        verify(notion, times(6)).appendBlockChildren(anyString(), anyString());
        verify(notion, never()).ensurePageParent(anyString(), anyString());
    }

    @Test
    void sortsSourcesBeforeBuildingParentsRegardlessOfDiscoveryOrder() throws Exception {
        setup();
        var reader = mock(DocumentationMarkdownReader.class);
        var a = new DocumentationMarkdownReader.DocumentationSource("project", "A", "# A", "a/deep/a.md");
        var b = new DocumentationMarkdownReader.DocumentationSource("project", "B", "# B", "b/b.md");
        var c = new DocumentationMarkdownReader.DocumentationSource("project", "C", "# C", "a/z.md");
        when(reader.discover("project", documentation)).thenReturn(List.of(b, c, a));
        service(repository, reader).synchronize("project", documentation);
        assertThat(new ArrayList<>(titles.values())).containsExactly("project", "a", "deep", "A", "C", "b", "B");
        when(reader.discover("project", documentation)).thenReturn(List.of(a, b, c));
        assertThat(service(repository, reader).synchronize("project", documentation).unchanged()).isEqualTo(3);
        verify(notion, times(7)).createChildPage(anyString(), anyString(), eq("[]"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void recoversDirectoryAfterLostResponseOrFailedIdSave(boolean lostResponse) throws Exception {
        setup();
        file("a/b/guide.md");
        repository.save(documentation, new DocumentationNotionMapping("project-page", Map.of()));
        if (lostResponse) {
            when(notion.createChildPage(eq("project-page"), anyString(), eq("[]"))).thenAnswer(call -> {
                titles.put("folder", call.getArgument(1));
                parents.put("folder", "project-page");
                throw new IllegalStateException("lost response");
            });
        }
        var failing = new DocumentationNotionMappingRepository() {
            @Override public void save(Path path, DocumentationNotionMapping mapping) {
                if (!lostResponse && mapping.directories().containsKey(dir("a"))) {
                    throw new IllegalStateException("disk failed");
                }
                super.save(path, mapping);
            }
        };
        assertThatThrownBy(() -> service(failing, new DocumentationMarkdownReader())
                .synchronize("project", documentation)).isInstanceOf(IllegalStateException.class);
        assertThat(repository.load(documentation).documents()).isEmpty();
        verify(notion, times(1)).createChildPage(anyString(), anyString(), eq("[]"));
        assertThat(sync().created()).isEqualTo(1);
        verify(notion, times(1)).createChildPage(eq("project-page"), anyString(), eq("[]"));
        verify(notion, times(3)).createChildPage(anyString(), anyString(), eq("[]"));
        assertThat(repository.load(documentation).directories()).hasSize(2);
    }

    @Test
    void childFailureKeepsParentsAndRetryDoesNotRecreateThem() throws Exception {
        setup();
        file("a/b/guide.md");
        when(notion.appendBlockChildren(anyString(), anyString()))
                .thenThrow(new IllegalStateException("child failed")).thenReturn("{}");
        assertThatThrownBy(this::sync).hasMessage("child failed");
        assertThat(repository.load(documentation).directories()).hasSize(2);
        assertThat(repository.load(documentation).documents()).hasSize(1);
        assertThat(sync().updated()).isEqualTo(1);
        verify(notion, times(2)).createChildPage(anyString(), startsWith("sbm-sync-pending:directory:"), eq("[]"));
        verify(notion, times(4)).createChildPage(anyString(), anyString(), eq("[]"));
    }

    @Test
    void unresolvedParentFailureNeverCreatesChildren() throws Exception {
        setup();
        file("a/b/guide.md");
        repository.save(documentation, new DocumentationNotionMapping("project-page", Map.of()));
        when(notion.createChildPage(eq("project-page"), anyString(), eq("[]")))
                .thenThrow(new IllegalStateException("parent failed"));
        assertThatThrownBy(this::sync).hasMessage("parent failed");
        assertThatThrownBy(this::sync).hasMessageContaining("Unresolved Notion creation");
        verify(notion, times(1)).createChildPage(anyString(), anyString(), eq("[]"));
        assertThat(repository.load(documentation).directories()).isEmpty();
        assertThat(repository.load(documentation).documents()).isEmpty();
    }

    @Test
    void legacyUnchangedDocumentIsMovedByKnownIdWithoutRewritingContent() throws Exception {
        setup();
        file("a/guide.md");
        sync();
        var mapping = repository.load(documentation);
        String page = mapping.documents().get(doc("a/guide.md"));
        String hash = mapping.hashes().get(doc("a/guide.md"));
        // Serialize the actual Stage 1/2 format, without any hierarchy fields.
        var json = new tools.jackson.databind.json.JsonMapper();
        Files.writeString(repository.mappingPath(documentation), json.writeValueAsString(Map.of(
                "projectPageId", mapping.projectPageId(), "documents", mapping.documents(),
                "hashes", mapping.hashes(), "pendingCreates", Map.of())));
        parents.put(page, mapping.projectPageId());
        clearInvocations(notion);
        assertThat(sync().unchanged()).isEqualTo(1);
        var migrated = repository.load(documentation);
        assertThat(migrated.documents()).containsEntry(doc("a/guide.md"), page);
        assertThat(migrated.hashes()).containsEntry(doc("a/guide.md"), hash);
        verify(notion).ensurePageParent(page, migrated.directories().get(dir("a")));
        verify(notion, never()).appendBlockChildren(anyString(), anyString());
        verify(notion, times(1)).createChildPage(anyString(), startsWith("sbm-sync-pending:directory:"), eq("[]"));
        assertThat(sync().unchanged()).isEqualTo(1);
        verify(notion, times(1)).ensurePageParent(anyString(), anyString());
    }

    @Test
    void oldPendingDocumentIsRecoveredFromProjectRootThenReparented() throws Exception {
        setup();
        file("a/guide.md");
        var mapping = new DocumentationNotionMapping("project-page", Map.of());
        mapping.pendingCreates().put(doc("a/guide.md"), "old-marker");
        repository.save(documentation, mapping);
        titles.put("existing-doc", "old-marker");
        parents.put("existing-doc", "project-page");
        assertThat(sync().created()).isEqualTo(1);
        verify(notion).findChildPageByCreationMarker("project-page", "old-marker");
        verify(notion, times(1)).createChildPage(anyString(), startsWith("sbm-sync-pending:directory:"), eq("[]"));
        verify(notion, times(1)).createChildPage(anyString(), anyString(), eq("[]"));
        assertThat(repository.load(documentation).documents()).containsEntry(doc("a/guide.md"), "existing-doc");
    }

    @Test
    void changedPathHasNewIdentityEvenWithIdenticalContent() throws Exception {
        setup();
        file("a/guide.md");
        sync();
        String oldId = repository.load(documentation).documents().get(doc("a/guide.md"));
        Files.createDirectories(documentation.resolve("b"));
        Files.move(documentation.resolve("a/guide.md"), documentation.resolve("b/guide.md"));
        assertThat(sync().created()).isEqualTo(1);
        var mapping = repository.load(documentation);
        assertThat(mapping.documents()).containsEntry(doc("a/guide.md"), oldId);
        assertThat(mapping.documents().get(doc("b/guide.md"))).isNotEqualTo(oldId);
        verify(notion, never()).ensurePageParent(anyString(), anyString());
        verify(notion, times(5)).createChildPage(anyString(), anyString(), eq("[]"));
    }

    @Test
    void failedParentSaveAfterMoveKeepsIdentityAndHashForRetry() throws Exception {
        setup();
        file("a/guide.md");
        sync();
        var mapping = repository.load(documentation);
        String pageId = mapping.documents().get(doc("a/guide.md"));
        String oldHash = mapping.hashes().get(doc("a/guide.md"));
        mapping.parents().remove(doc("a/guide.md"));
        repository.save(documentation, mapping);
        parents.put(pageId, mapping.projectPageId());
        clearInvocations(notion);
        var failing = new DocumentationNotionMappingRepository() {
            @Override public void save(Path path, DocumentationNotionMapping current) {
                if (current.parents().containsKey(doc("a/guide.md"))) {
                    throw new IllegalStateException("parent save failed");
                }
                super.save(path, current);
            }
        };
        assertThatThrownBy(() -> service(failing, new DocumentationMarkdownReader())
                .synchronize("project", documentation)).hasMessage("parent save failed");
        assertThat(repository.load(documentation).hashes()).containsEntry(doc("a/guide.md"), oldHash);
        assertThat(repository.load(documentation).documents()).containsEntry(doc("a/guide.md"), pageId);
        assertThat(sync().unchanged()).isEqualTo(1);
        verify(notion, never()).createChildPage(anyString(), anyString(), anyString());
        verify(notion, never()).appendBlockChildren(anyString(), anyString());
    }

    @Test
    void sameDirectoryNamesHaveDifferentIdsAcrossPathsAndProjects() {
        assertThat(dir("a/common")).isNotEqualTo(dir("b/common"));
        assertThat(dir("a/common")).isNotEqualTo(ids.generateDirectory("other", "a/common"));
        assertThat(dir("a/common")).isNotEqualTo(doc("a/common"));
        assertThat(dir("a/common")).isEqualTo(ids.generateDirectory("project", "a/common"));
    }
}
