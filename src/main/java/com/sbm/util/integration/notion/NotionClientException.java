package com.sbm.util.integration.notion;

/** Deliberately excludes raw HTTP bodies, headers and transport/parser causes. */
public class NotionClientException extends RuntimeException {
    private final Integer status;
    private final String operation;
    private final String code;

    public NotionClientException(Integer status, String operation, String code, String description) {
        super(operation + ": " + description + (status == null ? "" : " (HTTP " + status + ")"));
        this.status = status;
        this.operation = operation;
        this.code = code;
    }

    public Integer status() { return status; }
    public String operation() { return operation; }
    public String code() { return code; }
}
