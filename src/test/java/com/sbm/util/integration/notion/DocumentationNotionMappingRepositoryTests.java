package com.sbm.util.integration.notion;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentationNotionMappingRepositoryTests {

    @Test
    void missingMappingLoadsEmpty() throws Exception {
        Path documentation = Files.createTempDirectory("documentation");
        DocumentationNotionMapping mapping = new DocumentationNotionMappingRepository().load(documentation);

        assertThat(mapping.projectPageId()).isNull();
        assertThat(mapping.documents()).isEmpty();
    }

    @Test
    void savesAndLoadsMappingDeterministically() throws Exception {
        Path documentation = Files.createTempDirectory("documentation");
        LinkedHashMap<String, String> documents = new LinkedHashMap<>();
        documents.put("stable-a", "page-a");
        documents.put("stable-b", "page-b");
        DocumentationNotionMapping expected = new DocumentationNotionMapping("project-page", documents);
        DocumentationNotionMappingRepository repository = new DocumentationNotionMappingRepository();

        expected.directories().put("directory:abc", "folder-page");
        expected.parents().put("stable-a", "folder-page");
        expected.parents().put("directory:abc", "project-page");
        expected.hashes().put("stable-a", "abc123");
        expected.pendingCreates().put("stable-b", "sbm-sync-pending:stable-b:token");
        repository.save(documentation, expected);

        assertThat(repository.load(documentation).projectPageId()).isEqualTo("project-page");
        assertThat(repository.load(documentation).documents()).containsExactlyEntriesOf(documents);
        assertThat(repository.load(documentation).hashes()).containsEntry("stable-a", "abc123");
        assertThat(repository.load(documentation).pendingCreates()).containsExactlyEntriesOf(expected.pendingCreates());
        assertThat(repository.load(documentation).directories()).containsExactlyEntriesOf(expected.directories());
        assertThat(repository.load(documentation).parents()).containsExactlyEntriesOf(expected.parents());
        String saved = Files.readString(documentation.resolve(".sbm/notion-sync-map.json"));
        repository.save(documentation, repository.load(documentation));
        assertThat(Files.readString(repository.mappingPath(documentation))).isEqualTo(saved);
    }

    @Test
    void loadsLegacyMappingWithoutHashes() throws Exception {
        Path documentation = Files.createTempDirectory("documentation");
        var repository = new DocumentationNotionMappingRepository();
        Files.createDirectories(repository.mappingPath(documentation).getParent());
        Files.writeString(repository.mappingPath(documentation),
                "{\"projectPageId\":\"project\",\"documents\":{\"stable\":\"page\"}}");
        assertThat(repository.load(documentation).documents()).containsEntry("stable", "page");
        assertThat(repository.load(documentation).hashes()).isEmpty();
    }

    @Test
    void loadsStageOneMappingWithoutPendingCreates() throws Exception {
        Path documentation = Files.createTempDirectory("documentation");
        var repository = new DocumentationNotionMappingRepository();
        Files.createDirectories(repository.mappingPath(documentation).getParent());
        Files.writeString(repository.mappingPath(documentation),
                "{\"projectPageId\":\"project\",\"documents\":{\"stable\":\"page\"},"
                        + "\"hashes\":{\"stable\":\"hash\"}}");
        var mapping = repository.load(documentation);
        assertThat(mapping.documents()).containsEntry("stable", "page");
        assertThat(mapping.hashes()).containsEntry("stable", "hash");
        assertThat(mapping.pendingCreates()).isEmpty();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void failedReplacementPreservesPreviousJsonAndCleansTemporary(boolean unsupported) throws Exception {
        Path documentation = Files.createTempDirectory("documentation");
        var repository = new DocumentationNotionMappingRepository();
        var mapping = new DocumentationNotionMapping("project", java.util.Map.of("stable", "page"));
        mapping.hashes().put("stable", "old-hash");
        repository.save(documentation, mapping);
        String previous = Files.readString(repository.mappingPath(documentation));
        var failing = new DocumentationNotionMappingRepository() {
            @Override void replaceAtomically(Path temporary, Path destination) throws java.io.IOException {
                // A fully serialized new file exists, but it must not damage the old mapping.
                assertThat(Files.readString(temporary)).contains("new-hash");
                if (unsupported) {
                    throw new java.nio.file.AtomicMoveNotSupportedException(
                            temporary.toString(), destination.toString(), "unsupported");
                }
                throw new java.io.IOException("simulated persistence failure");
            }
        };
        mapping.hashes().put("stable", "new-hash");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> failing.save(documentation, mapping))
                .isInstanceOf(IllegalStateException.class);
        assertThat(Files.readString(repository.mappingPath(documentation))).isEqualTo(previous);
        assertThat(repository.load(documentation).hashes()).containsEntry("stable", "old-hash");
        try (var files = Files.list(documentation.resolve(".sbm"))) {
            assertThat(files.toList()).containsExactly(repository.mappingPath(documentation));
        }
    }
}
