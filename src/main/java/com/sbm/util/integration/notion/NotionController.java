package com.sbm.util.integration.notion;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.http.HttpStatus;

@RestController
@RequestMapping("/api/notion")
public class NotionController {

    private final NotionService notionService;
    private final NotionDocumentationSyncService notionDocumentationSyncService;

    public NotionController(
            NotionService notionService,
            NotionDocumentationSyncService notionDocumentationSyncService
    ) {
        this.notionService = notionService;
        this.notionDocumentationSyncService = notionDocumentationSyncService;
    }

    @GetMapping(value = "/root-page", produces = MediaType.APPLICATION_JSON_VALUE)
    public String retrieveRootPage() {
        return notionService.retrieveRootPage();
    }

    @PostMapping(value = "/documentation/sync", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public DocumentationSyncResult synchronize(@Valid @RequestBody SyncRequest request) {
        return notionDocumentationSyncService.synchronize(request.project(), Path.of(request.documentationPath()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public java.util.Map<String, String> invalidInput() {
        return java.util.Map.of("error", "Invalid synchronization parameters");
    }

    record SyncRequest(@NotBlank String project, @NotBlank String documentationPath) {
    }
}