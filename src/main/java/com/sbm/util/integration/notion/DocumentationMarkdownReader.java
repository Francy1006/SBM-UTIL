package com.sbm.util.integration.notion;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

@Service
public class DocumentationMarkdownReader {

    public List<DocumentationSource> discover(String project, Path documentationPath) {
        try (Stream<Path> paths = Files.walk(documentationPath)) {
            return paths
                    .filter(path -> Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> path.toString().endsWith(".md"))
                    .filter(path -> !path.startsWith(documentationPath.resolve(".sbm")))
                    .map(path -> read(project, documentationPath, path))
                    .sorted(Comparator.comparing(DocumentationSource::sourcePath))
                    .toList();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to discover Markdown files: " + documentationPath, exception);
        }
    }

    private DocumentationSource read(String project, Path documentationPath, Path path) {
        try {
            if (!path.toRealPath().startsWith(documentationPath.toRealPath())) {
                throw new IllegalArgumentException("Markdown resolves outside documentationPath");
            }
            String markdown;
            try (var channel = Files.newByteChannel(path, java.util.Set.of(
                    java.nio.file.StandardOpenOption.READ, java.nio.file.LinkOption.NOFOLLOW_LINKS));
                 var reader = java.nio.channels.Channels.newReader(channel, StandardCharsets.UTF_8)) {
                StringBuilder text = new StringBuilder();
                char[] buffer = new char[4096];
                int count;
                while ((count = reader.read(buffer)) != -1) text.append(buffer, 0, count);
                markdown = text.toString();
            }
            String sourcePath = documentationPath.relativize(path).toString().replace(path.getFileSystem().getSeparator(), "/");
            return new DocumentationSource(project, title(markdown, path), markdown, sourcePath);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read Markdown file: " + path, exception);
        }
    }

    private String title(String markdown, Path path) {
        return markdown.lines()
                .filter(line -> line.startsWith("# "))
                .map(line -> line.substring(2))
                .findFirst()
                .orElseGet(() -> {
                    String fileName = path.getFileName().toString();
                    return fileName.substring(0, fileName.length() - ".md".length());
                });
    }

    public record DocumentationSource(String project, String title, String markdown, String sourcePath) {
    }
}