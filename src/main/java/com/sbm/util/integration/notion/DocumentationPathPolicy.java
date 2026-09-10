package com.sbm.util.integration.notion;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class DocumentationPathPolicy {
    private final Path root;
    @Autowired
    public DocumentationPathPolicy(NotionProperties properties) { this(properties.documentationRoot()); }
    public DocumentationPathPolicy(Path root) { this.root = root.toAbsolutePath().normalize(); }

    public Path validate(String project, Path requested) {
        requireText(project, "project");
        if (project.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("project contains control characters");
        }
        if (requested == null || requested.toString().isBlank()) {
            throw new IllegalArgumentException("documentationPath is required");
        }
        Path candidate = requested.isAbsolute() ? requested : root.resolve(requested);
        if (!candidate.normalize().startsWith(root)) {
            throw new IllegalArgumentException("documentationPath is outside the authorized root");
        }
        try {
            // Resolve BEFORE normalizing away ..: symlinks can change its meaning.
            Path actual = candidate.toRealPath();
            if (!actual.startsWith(root.toRealPath()) || !Files.isDirectory(actual)) {
                throw new IllegalArgumentException("documentationPath is outside the authorized root or is not a directory");
            }
            return actual;
        } catch (IOException exception) {
            throw new IllegalArgumentException("documentationPath is not accessible");
        }
    }

    static void requireText(String value, String field) {
        if (value == null || value.isBlank() || value.indexOf(0) >= 0) {
            throw new IllegalArgumentException(field + " is required");
        }
    }
}
