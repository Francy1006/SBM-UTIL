package com.sbm.util.integration.notion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NotionSyncRecoveryTests {

    @TempDir Path documentation;
    private final DocumentationNotionMappingRepository repository = new DocumentationNotionMappingRepository();
    private final NotionService notion = mock(NotionService.class);
    private final Map<String, String> remoteTitles = new LinkedHashMap<>();

    private void remote() {
        when(notion.rootPageId()).thenReturn("root");
        when(notion.createChildPage(anyString(), anyString(), eq("[]"))).thenAnswer(invocation -> {
            String marker = invocation.getArgument(1);
            // The recovery intent must already be on disk before the remote side effect.
            assertThat(repository.load(documentation).pendingCreates()).containsValue(marker);
            String id = "page-" + remoteTitles.size();
            remoteTitles.put(id, marker);
            return "{\"id\":\"" + id + "\"}";
        });
        when(notion.retrieveBlockChildren(anyString())).thenReturn("{\"results\":[]}");
        when(notion.findChildPageByCreationMarker(anyString(), anyString())).thenAnswer(invocation ->
                remoteTitles.entrySet().stream()
                        .filter(entry -> entry.getValue().equals(invocation.getArgument(1)))
                        .map(Map.Entry::getKey).findFirst().orElse(null));
        when(notion.updatePageTitle(anyString(), anyString())).thenAnswer(invocation -> {
            remoteTitles.put(invocation.getArgument(0), invocation.getArgument(1));
            return "{}";
        });
    }

    private NotionDocumentationSyncService service(DocumentationNotionMappingRepository repo) {
        return new NotionDocumentationSyncService(notion, new NotionMarkdownBlockMapper(), repo,
                new DocumentationMarkdownReader(),
                new DocumentationPagePreparationService(new DocumentationPageFactory(
                        new DocumentationStableIdGenerator())), new DocumentationPathPolicy(Path.of(System.getProperty("java.io.tmpdir"))));
    }

    private String stable(String file) {
        return new DocumentationStableIdGenerator().generate("project", file);
    }

    @Test
    void persistsEachDocumentAndRetriesAfterLaterDocumentFailsWithoutDuplicatePages() throws Exception {
        remote();
        Files.writeString(documentation.resolve("a.md"), "# A");
        Files.writeString(documentation.resolve("b.md"), "# B");
        Files.writeString(documentation.resolve("c.md"), "# C");
        when(notion.appendBlockChildren(eq("page-2"), anyString()))
                .thenThrow(new IllegalStateException("append failed")).thenReturn("{}");

        assertThatThrownBy(() -> service(repository).synchronize("project", documentation))
                .hasMessage("append failed");
        var saved = repository.load(documentation);
        assertThat(saved.projectPageId()).isEqualTo("page-0");
        assertThat(saved.documents()).containsEntry(stable("a.md"), "page-1")
                .containsEntry(stable("b.md"), "page-2");
        assertThat(saved.hashes()).containsKey(stable("a.md")).doesNotContainKey(stable("b.md"));

        assertThat(service(repository).synchronize("project", documentation))
                .isEqualTo(new DocumentationSyncResult("project", 3, 1, 1, 1));
        verify(notion, times(1)).createChildPage(eq("root"), anyString(), eq("[]"));
        verify(notion, times(3)).createChildPage(eq("page-0"), anyString(), eq("[]"));
        assertThat(repository.load(documentation).hashes()).hasSize(3);
        assertThat(repository.load(documentation).pendingCreates()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void reconcilesSuccessfulPostWhenSavingReturnedIdFails(boolean projectCreation) throws Exception {
        remote();
        Files.writeString(documentation.resolve("a.md"), "# A");
        if (!projectCreation) {
            repository.save(documentation, new DocumentationNotionMapping("existing-project", Map.of()));
        }
        var failing = new DocumentationNotionMappingRepository() {
            private boolean failed;
            @Override public void save(Path path, DocumentationNotionMapping mapping) {
                boolean hasId = projectCreation ? mapping.projectPageId() != null : !mapping.documents().isEmpty();
                if (!failed && hasId) {
                    failed = true;
                    throw new IllegalStateException("disk failed after POST");
                }
                super.save(path, mapping);
            }
        };

        assertThatThrownBy(() -> service(failing).synchronize("project", documentation))
                .hasMessage("disk failed after POST");
        assertThat(repository.load(documentation).pendingCreates()).hasSize(1);
        if (projectCreation) {
            assertThat(repository.load(documentation).projectPageId()).isNull();
        } else {
            assertThat(repository.load(documentation).documents()).isEmpty();
        }

        assertThat(service(repository).synchronize("project", documentation).created()).isEqualTo(1);
        verify(notion, times(projectCreation ? 2 : 1)).createChildPage(anyString(), anyString(), eq("[]"));
        verify(notion, times(1)).findChildPageByCreationMarker(anyString(), anyString());
        assertThat(repository.load(documentation).hashes()).hasSize(1);
    }

    @Test
    void reconcilesPageWhenPostSucceededButResponseWasLost() throws Exception {
        remote();
        repository.save(documentation, new DocumentationNotionMapping("parent", Map.of()));
        Files.writeString(documentation.resolve("a.md"), "# A");
        when(notion.createChildPage(eq("parent"), anyString(), eq("[]"))).thenAnswer(invocation -> {
            remoteTitles.put("created", invocation.getArgument(1));
            throw new IllegalStateException("response lost");
        });
        assertThatThrownBy(() -> service(repository).synchronize("project", documentation))
                .hasMessage("response lost");
        service(repository).synchronize("project", documentation);
        verify(notion, times(1)).createChildPage(eq("parent"), anyString(), eq("[]"));
        assertThat(repository.load(documentation).documents()).containsEntry(stable("a.md"), "created");
    }

    @Test
    void unresolvedCreationNeverBlindlyRetriesPost() throws Exception {
        remote();
        repository.save(documentation, new DocumentationNotionMapping("parent", Map.of()));
        Files.writeString(documentation.resolve("a.md"), "# A");
        when(notion.createChildPage(eq("parent"), anyString(), eq("[]")))
                .thenThrow(new IllegalStateException("unknown outcome"));
        assertThatThrownBy(() -> service(repository).synchronize("project", documentation))
                .hasMessage("unknown outcome");
        assertThatThrownBy(() -> service(repository).synchronize("project", documentation))
                .hasMessageContaining("Unresolved Notion creation");
        verify(notion, times(1)).createChildPage(anyString(), anyString(), eq("[]"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"title", "read", "delete", "append"})
    void failedUpdateKeepsPageIdAndInvalidatesHashUntilFullSuccess(String step) throws Exception {
        remote();
        Files.writeString(documentation.resolve("a.md"), "# Original");
        service(repository).synchronize("project", documentation);
        String id = repository.load(documentation).documents().get(stable("a.md"));
        String originalHash = repository.load(documentation).hashes().get(stable("a.md"));
        Files.writeString(documentation.resolve("a.md"), "# Changed");
        when(notion.retrieveBlockChildren(id)).thenReturn("{\"results\":[{\"id\":\"old-block\"}]}");
        var failure = new IllegalStateException("failed " + step);
        switch (step) {
            case "title" -> when(notion.updatePageTitle(eq(id), anyString())).thenThrow(failure).thenReturn("{}");
            case "read" -> when(notion.retrieveBlockChildren(id)).thenThrow(failure).thenReturn("{\"results\":[]}");
            case "delete" -> doThrow(failure).doNothing().when(notion).deleteBlock("old-block");
            case "append" -> when(notion.appendBlockChildren(eq(id), anyString())).thenThrow(failure).thenReturn("{}");
            default -> throw new AssertionError(step);
        }
        assertThatThrownBy(() -> service(repository).synchronize("project", documentation)).isSameAs(failure);
        assertThat(repository.load(documentation).documents()).containsEntry(stable("a.md"), id);
        assertThat(repository.load(documentation).hashes()).doesNotContainKey(stable("a.md"));
        // Reverting the file must still repair a partially replaced remote document.
        Files.writeString(documentation.resolve("a.md"), "# Original");
        assertThat(service(repository).synchronize("project", documentation).updated()).isEqualTo(1);
        assertThat(repository.load(documentation).hashes()).containsEntry(stable("a.md"), originalHash);
        assertThat(service(repository).synchronize("project", documentation).unchanged()).isEqualTo(1);
        verify(notion, times(2)).createChildPage(anyString(), anyString(), eq("[]"));
    }

    @Test
    void failedFinalHashSaveRetriesUpdateWithoutCreatingPage() throws Exception {
        remote();
        Files.writeString(documentation.resolve("a.md"), "# A");
        var failing = new DocumentationNotionMappingRepository() {
            @Override public void save(Path path, DocumentationNotionMapping mapping) {
                if (!mapping.hashes().isEmpty()) {
                    throw new IllegalStateException("hash save failed");
                }
                super.save(path, mapping);
            }
        };
        assertThatThrownBy(() -> service(failing).synchronize("project", documentation))
                .hasMessage("hash save failed");
        assertThat(repository.load(documentation).documents()).hasSize(1);
        assertThat(repository.load(documentation).hashes()).isEmpty();
        assertThat(service(repository).synchronize("project", documentation).updated()).isEqualTo(1);
        verify(notion, times(2)).createChildPage(anyString(), anyString(), eq("[]"));
    }

    @Test
    void failedIntentSaveDoesNotCreateRemotePage() {
        remote();
        var failing = new DocumentationNotionMappingRepository() {
            @Override void replaceAtomically(Path temporary, Path destination) throws IOException {
                throw new IOException("disk failure");
            }
        };
        assertThatThrownBy(() -> service(failing).synchronize("project", documentation))
                .isInstanceOf(IllegalStateException.class);
        verify(notion, never()).createChildPage(anyString(), anyString(), anyString());
    }
}
