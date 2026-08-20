package com.sbm.util.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "sbm.security")
public record ServiceTokenProperties(String serviceToken) {
}