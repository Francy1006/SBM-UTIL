package com.sbm.util.integration.notion;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import org.springframework.stereotype.Service;

@Service
public class DocumentationStableIdGenerator {

    public String generate(String project, String sourcePath) {
        String baseValue = project.trim() + ":" + sourcePath.trim();
        byte[] digest = digest(baseValue);
        StringBuilder hexadecimal = new StringBuilder(digest.length * 2);

        for (byte value : digest) {
            hexadecimal.append(String.format("%02x", value));
        }

        return hexadecimal.toString();
    }

    public String generateDirectory(String project, String relativePath) {
        // Separate namespace; preserve directory spelling and segment boundaries.
        return "directory:" + java.util.HexFormat.of().formatHex(
                digest(project.length() + ":" + project + ":" + relativePath));
    }

    private byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}