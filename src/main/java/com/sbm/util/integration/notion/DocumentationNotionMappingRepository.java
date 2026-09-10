package com.sbm.util.integration.notion;

import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.nio.file.Path;
import java.util.LinkedHashMap;

@Repository
public class DocumentationNotionMappingRepository {

    private final JsonMapper json = JsonMapper.builder()
            .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    public <T> T withSyncLock(Path documentationPath, java.util.function.Supplier<T> work) {
        Path lockPath = mappingPath(documentationPath).resolveSibling("notion-sync.lock");
        try {
            Files.createDirectories(lockPath.getParent());
            try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, java.nio.file.LinkOption.NOFOLLOW_LINKS);
                 var lock = channel.tryLock()) {
                if (lock == null) throw new IllegalStateException("Notion sync already running for this documentation");
                return work.get();
            }
        } catch (java.nio.channels.OverlappingFileLockException exception) {
            throw new IllegalStateException("Notion sync already running for this documentation");
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to lock Notion mapping");
        }
    }

    private void validatePayload(tools.jackson.databind.JsonNode payload) {
        if (payload == null || !payload.isObject() || !payload.has("projectPageId")
                || !payload.path("documents").isObject()) {
            throw new IllegalStateException("Invalid Notion mapping structure");
        }
        var project = payload.path("projectPageId");
        if (!project.isNull() && (!project.isString() || project.asString().isBlank())) {
            throw new IllegalStateException("Invalid project page ID in Notion mapping");
        }
        for (String field : java.util.List.of("documents", "directories", "parents", "hashes", "pendingCreates")) {
            var entries = payload.path(field);
            if (entries.isMissingNode()) continue; // Legacy mappings lack newer sections.
            if (!entries.isObject()) throw new IllegalStateException("Invalid Notion mapping section: " + field);
            for (var entry : entries.properties()) {
                if (entry.getKey().isBlank() || !entry.getValue().isString() || entry.getValue().asString().isBlank()) {
                    throw new IllegalStateException("Invalid Notion mapping entry in " + field);
                }
            }
        }
        if (project.isNull() && (payload.path("documents").size() > 0 || payload.path("directories").size() > 0)) {
            throw new IllegalStateException("Notion mapping pages require a project page ID");
        }
    }

    public DocumentationNotionMapping load(Path documentationPath) {
        Path path = mappingPath(documentationPath);
        if (!Files.exists(path)) {
            return DocumentationNotionMapping.empty();
        }
        try {
            var payload = json.readTree(Files.readString(path, StandardCharsets.UTF_8));
            validatePayload(payload);
            var documents = new LinkedHashMap<String, String>();
            payload.path("documents").properties().forEach(entry ->
                    documents.put(entry.getKey(), entry.getValue().asString()));
            var mapping = new DocumentationNotionMapping(
                    payload.path("projectPageId").asString(null), documents);
            payload.path("hashes").properties().forEach(entry ->
                    mapping.hashes().put(entry.getKey(), entry.getValue().asString()));
            payload.path("pendingCreates").properties().forEach(entry ->
                    mapping.pendingCreates().put(entry.getKey(), entry.getValue().asString()));
            payload.path("directories").properties().forEach(entry ->
                    mapping.directories().put(entry.getKey(), entry.getValue().asString()));
            payload.path("parents").properties().forEach(entry ->
                    mapping.parents().put(entry.getKey(), entry.getValue().asString()));
            return mapping;
        } catch (tools.jackson.core.JacksonException exception) {
            throw new IllegalStateException("Invalid Notion mapping JSON");
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to load Notion mapping: " + path, exception);
        }
    }

    public void save(Path documentationPath, DocumentationNotionMapping mapping) {
        Path path = mappingPath(documentationPath);
        Path temporary = null;
        try {
            Files.createDirectories(path.getParent());
            var payload = new LinkedHashMap<String, Object>();
            payload.put("projectPageId", mapping.projectPageId());
            payload.put("documents", mapping.documents());
            payload.put("hashes", mapping.hashes());
            payload.put("pendingCreates", mapping.pendingCreates());
            payload.put("directories", mapping.directories());
            payload.put("parents", mapping.parents());
            temporary = Files.createTempFile(path.getParent(), "notion-sync-map-", ".tmp");
            Files.writeString(temporary, json.writerWithDefaultPrettyPrinter().writeValueAsString(payload)
                    + "\n", StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            replaceAtomically(temporary, path);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to save Notion mapping: " + path, exception);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // A leftover temporary file is never used as the authoritative mapping.
                }
            }
        }
    }

    void replaceAtomically(Path temporary, Path destination) throws IOException {
        // Fail closed if atomic replacement is unsupported: keep the previous valid mapping.
        Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
    }

    public Path mappingPath(Path documentationPath) {
        Path directory = documentationPath.resolve(".sbm");
        Path mapping = directory.resolve("notion-sync-map.json");
        if (Files.isSymbolicLink(directory) || Files.isSymbolicLink(mapping)) {
            throw new IllegalArgumentException("Notion mapping must not be a symbolic link");
        }
        return mapping;
    }
}
