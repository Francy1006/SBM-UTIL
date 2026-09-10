package com.sbm.util.integration.notion;

import org.springframework.stereotype.Service;

@Service
public class DocumentationPageFactory {

    private final DocumentationStableIdGenerator stableIdGenerator;

    public DocumentationPageFactory(DocumentationStableIdGenerator stableIdGenerator) {
        this.stableIdGenerator = stableIdGenerator;
    }

    public DocumentationPage create(
            String project,
            String title,
            String markdown,
            String sourcePath
    ) {
        String stableId = stableIdGenerator.generate(project, sourcePath);
        return new DocumentationPage(stableId, project, title, markdown, sourcePath);
    }
}