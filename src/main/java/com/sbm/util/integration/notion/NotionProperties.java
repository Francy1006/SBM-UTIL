package com.sbm.util.integration.notion;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import java.nio.file.Path;
import java.time.Duration;

@ConfigurationProperties(prefix = "sbm.notion")
public record NotionProperties(String apiToken, String apiVersion, String rootPageId,
                               Path documentationRoot, Duration connectTimeout, Duration requestTimeout) {
    @ConstructorBinding
    public NotionProperties {
        documentationRoot = documentationRoot == null ? Path.of("Documentation") : documentationRoot;
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(10) : connectTimeout;
        requestTimeout = requestTimeout == null ? Duration.ofSeconds(30) : requestTimeout;
        if (connectTimeout.isNegative() || connectTimeout.isZero()
                || requestTimeout.isNegative() || requestTimeout.isZero()) {
            throw new IllegalArgumentException("Notion timeouts must be positive");
        }
    }
    public NotionProperties(String token, String version, String root) {
        this(token, version, root, null, null, null);
    }
    @Override public String toString() { return "NotionProperties[credentials redacted]"; }
}
