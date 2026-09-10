package com.sbm.util.integration.notion;

public record DocumentationSyncResult(
        String project,
        int discovered,
        int created,
        int updated,
        int unchanged
) {
}