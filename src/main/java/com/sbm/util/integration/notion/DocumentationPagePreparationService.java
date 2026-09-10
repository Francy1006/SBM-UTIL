package com.sbm.util.integration.notion;

import org.springframework.stereotype.Service;

@Service
public class DocumentationPagePreparationService {

    private final DocumentationPageFactory documentationPageFactory;

    public DocumentationPagePreparationService(DocumentationPageFactory documentationPageFactory) {
        this.documentationPageFactory = documentationPageFactory;
    }

    public DocumentationPage prepare(
            String project,
            String title,
            String markdown,
            String sourcePath
    ) {
        return documentationPageFactory.create(project, title, markdown, sourcePath);
    }
}