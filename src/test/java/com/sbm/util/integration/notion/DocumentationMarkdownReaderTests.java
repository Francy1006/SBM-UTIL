package com.sbm.util.integration.notion;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentationMarkdownReaderTests {

    @Test
    void discoversMarkdownRecursivelyAndExcludesSbmDirectory() throws Exception {
        Path documentation = Files.createTempDirectory("documentation");
        Files.createDirectories(documentation.resolve("nested"));
        Files.createDirectories(documentation.resolve(".sbm"));
        Files.writeString(documentation.resolve("nested/guide.md"), "# Guide\nText", StandardCharsets.UTF_8);
        Files.writeString(documentation.resolve("fallback.md"), "Text", StandardCharsets.UTF_8);
        Files.writeString(documentation.resolve(".sbm/ignored.md"), "# Ignored", StandardCharsets.UTF_8);

        var pages = new DocumentationMarkdownReader().discover("SBM-UTIL", documentation);

        assertThat(pages).extracting(DocumentationMarkdownReader.DocumentationSource::sourcePath)
                .containsExactly("fallback.md", "nested/guide.md");
        assertThat(pages.get(0).title()).isEqualTo("fallback");
        assertThat(pages.get(1).title()).isEqualTo("Guide");
        assertThat(pages.get(1).project()).isEqualTo("SBM-UTIL");
    }
}