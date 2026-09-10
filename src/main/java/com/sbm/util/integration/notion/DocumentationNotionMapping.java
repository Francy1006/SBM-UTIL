package com.sbm.util.integration.notion;

import java.util.LinkedHashMap;
import java.util.Map;

public class DocumentationNotionMapping {

    private String projectPageId;
    private final Map<String, String> documents;
    private final Map<String, String> hashes = new LinkedHashMap<>();

    private final Map<String, String> directories = new LinkedHashMap<>();
    private final Map<String, String> parents = new LinkedHashMap<>();

    private final Map<String, String> pendingCreates = new LinkedHashMap<>();

    public DocumentationNotionMapping(String projectPageId, Map<String, String> documents) {
        this.projectPageId = projectPageId;
        this.documents = new LinkedHashMap<>(documents);
    }

    public static DocumentationNotionMapping empty() {
        return new DocumentationNotionMapping(null, Map.of());
    }

    public String projectPageId() {
        return projectPageId;
    }

    public void projectPageId(String projectPageId) {
        this.projectPageId = projectPageId;
    }

    public Map<String, String> directories() {
        return directories;
    }

    public Map<String, String> parents() {
        return parents;
    }

    public Map<String, String> pendingCreates() {
        return pendingCreates;
    }

    public Map<String, String> hashes() {
        return hashes;
    }

    public Map<String, String> documents() {
        return documents;
    }
}