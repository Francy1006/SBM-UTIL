package com.sbm.util.integration.notion;

public record DocumentationPage(
        String stableId,
        String project,
        String title,
        String markdown,
        String sourcePath
) {
}