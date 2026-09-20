package com.sbm.util.integration.notion;

import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.ResourceAccessException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

public class NotionClient {
    private static final int MAX_CHILDREN = 100;
    private static final String JSON_CHILDREN = "children";
    private static final String JSON_HAS_MORE = "has_more";
    private static final String JSON_RESULTS = "results";
    private static final String JSON_NEXT_CURSOR = "next_cursor";
    private static final String FIND_CHILD_PAGE_OPERATION = "findChildPage";
    private static final String JSON_CHILD_PAGE = "child_page";
    private static final String JSON_TITLE = "title";
    private static final String JSON_PARENT = "parent";
    private static final String JSON_PAGE_ID = "page_id";
    private static final String APPEND_BLOCK_CHILDREN_OPERATION = "appendBlockChildren";
    private static final String RETRIEVE_PAGE_OPERATION = "retrievePage";
    private static final String MOVE_PAGE_OPERATION = "movePage";
    private static final String UPDATE_PAGE_TITLE_OPERATION = "updatePageTitle";
    private static final String DELETE_BLOCK_OPERATION = "deleteBlock";
    private static final String CREATE_CHILD_PAGE_OPERATION = "createChildPage";
    private static final Map<String, String> ERRORS = Map.ofEntries(
            Map.entry("unauthorized", "Authentication rejected"),
            Map.entry("restricted_resource", "Access denied"),
            Map.entry("object_not_found", "Resource not found"),
            Map.entry("validation_error", "Request validation failed"),
            Map.entry("invalid_json", "Request JSON rejected"),
            Map.entry("rate_limited", "Rate limit exceeded"),
            Map.entry("conflict_error", "Remote conflict"),
            Map.entry("internal_server_error", "Remote internal error"),
            Map.entry("service_unavailable", "Remote service unavailable"),
            Map.entry("gateway_timeout", "Remote gateway timed out"),
            Map.entry("service_overload", "Remote service overloaded"));
    private final RestClient restClient;
    private final JsonMapper json = new JsonMapper();

    private static final int MAX_ATTEMPTS = 3;
    private final Sleeper sleeper;

    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    public NotionClient(RestClient restClient) { this(restClient, Thread::sleep); }

    NotionClient(RestClient restClient, Sleeper sleeper) {
        this.restClient = restClient;
        this.sleeper = sleeper;
    }

    private record Result(String body, NotionClientException error, String retryAfter) {}

    private String send(String operation, boolean idempotent,
                        Supplier<RestClient.RequestHeadersSpec<?>> request) {
        for (int attempt = 1; ; attempt++) {
            NotionClientException failure;
            String retryAfter = null;
            boolean retryable;
            try {
                // Each attempt gets a fresh request; exchange closes the response before waiting.
                Result result = request.get().exchange((httpRequest, response) -> readResult(operation, response));
                if (result.error() == null) return result.body();
                failure = result.error();
                int status = failure.status();
                retryable = isRetryableStatus(status);
                if (status == 429 || status == 529) retryAfter = result.retryAfter();
            } catch (ResourceAccessException exception) {
                failure = transportFailure(operation, exception);
                retryable = true;
            }
            if (!idempotent || !retryable || attempt == MAX_ATTEMPTS) throw failure;
            waitBeforeRetry(operation, retryAfter, attempt);
        }
    }

    private Result readResult(String operation, ClientHttpResponse response) throws IOException {
        int status = response.getStatusCode().value();
        if (status >= 200 && status < 300) {
            return new Result(new String(response.getBody().readAllBytes(),
                    StandardCharsets.UTF_8), null, null);
        }
        String code = errorCode(status, response);
        return new Result(null, new NotionClientException(status, operation, code,
                ERRORS.getOrDefault(code, "Remote request failed")),
                response.getHeaders().getFirst("Retry-After"));
    }

    private String errorCode(int status, ClientHttpResponse response) {
        // Never echo arbitrary remote messages: they may contain submitted secrets.
        String code = status == 529 ? "service_overload" : "http_error";
        try {
            JsonNode error = json.readTree(response.getBody().readNBytes(8192));
            if (error != null && ERRORS.containsKey(error.path("code").asString(""))) {
                code = error.path("code").asString();
            }
        } catch (JacksonException | IOException ignored) {
            // Status and operation remain useful for malformed error bodies.
        }
        return code;
    }

    private boolean isRetryableStatus(int status) {
        return status == 429 || status == 529 || status == 500
                || status == 502 || status == 503 || status == 504;
    }

    private NotionClientException transportFailure(String operation, ResourceAccessException exception) {
        Throwable cause = exception;
        boolean timedOut = false;
        while (cause != null) {
            timedOut |= cause instanceof java.net.http.HttpTimeoutException
                    || cause instanceof java.net.SocketTimeoutException;
            cause = cause.getCause();
        }
        // Repeating DELETE is safe, but exhaustion must not imply remote success.
        return new NotionClientException(null, operation, timedOut ? "timeout" : "transport_error",
                timedOut ? "Request timed out; remote outcome may be uncertain"
                        : "Connection or response failed; remote outcome may be uncertain");
    }

    private void waitBeforeRetry(String operation, String retryAfter, int attempt) {
        try {
            sleeper.sleep(retryDelay(retryAfter, attempt));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new NotionClientException(null, operation, "interrupted",
                    "Retry interrupted; remote outcome may be uncertain");
        }
    }

    private long retryDelay(String retryAfter, int attempt) {
        if (retryAfter != null) {
            try {
                long seconds = Long.parseLong(retryAfter.trim());
                if (seconds >= 0) return Math.multiplyExact(seconds, 1000L);
            } catch (NumberFormatException | ArithmeticException ignored) {
                // Also accept the HTTP-date form of Retry-After.
            }
            try {
                return Math.max(0, Duration.between(java.time.Instant.now(),
                        ZonedDateTime.parse(retryAfter.trim(), DateTimeFormatter.RFC_1123_DATE_TIME)
                                .toInstant()).toMillis());
            } catch (DateTimeParseException | ArithmeticException ignored) {
                // Invalid headers fall back to bounded exponential backoff with jitter.
            }
        }
        long ceiling = Math.min(8000L, 500L << (attempt - 1));
        return ThreadLocalRandom.current().nextLong(ceiling / 2, ceiling + 1);
    }

    private ObjectNode object(String response, String operation) {
        try {
            JsonNode value = json.readTree(response);
            if (value instanceof ObjectNode node) return node;
        } catch (JacksonException exception) {
            throw invalid(operation);
        }
        throw invalid(operation);
    }

    private NotionClientException invalid(String operation) {
        return new NotionClientException(null, operation, "invalid_response", "Invalid or incomplete Notion response");
    }

    private String requiredId(JsonNode node, String operation) {
        JsonNode id = node.path("id");
        if (!id.isString() || id.asString().isBlank()) throw invalid(operation);
        return id.asString();
    }

    private void id(String value) {
        DocumentationPathPolicy.requireText(value, "Notion identifier");
        if (!value.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("Invalid Notion identifier");
        }
    }

    public String retrievePage(String pageId) {
        id(pageId);
        String response = send(RETRIEVE_PAGE_OPERATION, true, () -> restClient.get().uri("/pages/{pageId}", pageId));
        requiredId(object(response, RETRIEVE_PAGE_OPERATION), RETRIEVE_PAGE_OPERATION);
        return response;
    }

    private String blockChildrenPath(String pageId) {
        return String.join("/", "", "blocks", pageId, JSON_CHILDREN);
    }

    public String retrieveBlockChildren(String pageId) {
        id(pageId);
        String operation = "retrieveBlockChildren";
        String response = send(operation, true, () -> restClient.get().uri(blockChildrenPath(pageId)));
        ObjectNode payload = list(response, operation, true);
        if (!payload.path(JSON_HAS_MORE).asBoolean()) return response;
        var results = json.createArrayNode();
        payload.path(JSON_RESULTS).forEach(results::add);
        ObjectNode current = payload;
        var cursors = new HashSet<String>();
        while (current.path(JSON_HAS_MORE).asBoolean()) {
            String cursor = current.path(JSON_NEXT_CURSOR).asString();
            if (!cursors.add(cursor)) throw invalid(operation);
            current = list(send(operation, true, () -> restClient.get().uri(builder -> builder
                    .path(blockChildrenPath(pageId)).queryParam("start_cursor", cursor).build())),
                    operation, true);
            current.path(JSON_RESULTS).forEach(results::add);
        }
        payload.set(JSON_RESULTS, results);
        payload.put(JSON_HAS_MORE, false);
        payload.putNull(JSON_NEXT_CURSOR);
        return json.writeValueAsString(payload);
    }

    private ObjectNode list(String response, String operation, boolean paginated) {
        ObjectNode payload = object(response, operation);
        if (!payload.path(JSON_RESULTS).isArray()) throw invalid(operation);
        for (JsonNode block : payload.path(JSON_RESULTS)) requiredId(block, operation);
        if (paginated && (!payload.path(JSON_HAS_MORE).isBoolean()
                || (payload.path(JSON_HAS_MORE).asBoolean()
                && (!payload.path(JSON_NEXT_CURSOR).isString() || payload.path(JSON_NEXT_CURSOR).asString().isBlank())))) {
            throw invalid(operation);
        }
        return payload;
    }

    public String findChildPageByCreationMarker(String parentPageId, String marker) {
        DocumentationPathPolicy.requireText(marker, "creation marker");
        JsonNode children = object(retrieveBlockChildren(parentPageId), FIND_CHILD_PAGE_OPERATION).path(JSON_RESULTS);
        String pageId = null;
        for (JsonNode child : children) {
            if (JSON_CHILD_PAGE.equals(child.path("type").asString())) {
                if (!child.path(JSON_CHILD_PAGE).path(JSON_TITLE).isString()) throw invalid(FIND_CHILD_PAGE_OPERATION);
                if (marker.equals(child.path(JSON_CHILD_PAGE).path(JSON_TITLE).asString())) {
                    if (pageId != null) {
                        throw new NotionClientException(null, FIND_CHILD_PAGE_OPERATION, "ambiguous_creation",
                                "Multiple Notion pages match creation marker");
                    }
                    pageId = requiredId(child, FIND_CHILD_PAGE_OPERATION);
                }
            }
        }
        return pageId;
    }

    public void ensurePageParent(String pageId, String parentPageId) {
        id(parentPageId);
        JsonNode page = object(retrievePage(pageId), "ensurePageParent");
        JsonNode parent = page.path(JSON_PARENT).path(JSON_PAGE_ID);
        if (!parent.isString() || parent.asString().isBlank()) throw invalid("ensurePageParent");
        if (parent.asString().replace("-", "").equals(parentPageId.replace("-", ""))) return;
        var body = json.createObjectNode();
        body.putObject(JSON_PARENT).put("type", JSON_PAGE_ID).put(JSON_PAGE_ID, parentPageId);
        String response = send(MOVE_PAGE_OPERATION, false, () -> restClient.post().uri("/pages/{pageId}/move", pageId)
                .contentType(MediaType.APPLICATION_JSON).body(json.writeValueAsString(body)));
        requiredId(object(response, MOVE_PAGE_OPERATION), MOVE_PAGE_OPERATION);
    }

    public String updatePageTitle(String pageId, String title) {
        id(pageId);
        var body = json.createObjectNode();
        body.set("properties", titleProperties(title));
        String response = send(UPDATE_PAGE_TITLE_OPERATION, false, () -> restClient.patch().uri("/pages/{pageId}", pageId)
                .contentType(MediaType.APPLICATION_JSON).body(json.writeValueAsString(body)));
        requiredId(object(response, UPDATE_PAGE_TITLE_OPERATION), UPDATE_PAGE_TITLE_OPERATION);
        return response;
    }

    public void deleteBlock(String blockId) {
        id(blockId);
        String response = send(DELETE_BLOCK_OPERATION, true, () -> restClient.delete().uri("/blocks/{blockId}", blockId));
        requiredId(object(response, DELETE_BLOCK_OPERATION), DELETE_BLOCK_OPERATION);
    }

    public String appendBlockChildren(String pageId, String childrenJson) {
        id(pageId);
        JsonNode children = children(childrenJson);
        var results = json.createArrayNode();
        String response = null;
        for (int start = 0; start < children.size(); start += MAX_CHILDREN) {
            response = appendChunk(pageId, chunk(children, start));
            object(response, APPEND_BLOCK_CHILDREN_OPERATION).path(JSON_RESULTS).forEach(results::add);
        }
        if (response != null && children.size() <= MAX_CHILDREN) return response;
        var payload = json.createObjectNode();
        payload.set(JSON_RESULTS, results);
        payload.put(JSON_HAS_MORE, false);
        payload.putNull(JSON_NEXT_CURSOR);
        return json.writeValueAsString(payload);
    }

    private String appendChunk(String pageId, JsonNode children) {
        var body = json.createObjectNode();
        body.set(JSON_CHILDREN, children);
        String response = send(APPEND_BLOCK_CHILDREN_OPERATION, false, () -> restClient.patch().uri(blockChildrenPath(pageId))
                .contentType(MediaType.APPLICATION_JSON).body(json.writeValueAsString(body)));
        list(response, APPEND_BLOCK_CHILDREN_OPERATION, false);
        return response;
    }

    public String createChildPage(String parentPageId, String title, String childrenJson) {
        id(parentPageId);
        JsonNode children = children(childrenJson);
        var body = json.createObjectNode();
        body.putObject(JSON_PARENT).put(JSON_PAGE_ID, parentPageId);
        body.set("properties", titleProperties(title));
        body.set(JSON_CHILDREN, chunk(children, 0));
        String response = send(CREATE_CHILD_PAGE_OPERATION, false, () -> restClient.post().uri("/pages")
                .contentType(MediaType.APPLICATION_JSON).body(json.writeValueAsString(body)));
        String pageId = requiredId(object(response, CREATE_CHILD_PAGE_OPERATION), CREATE_CHILD_PAGE_OPERATION);
        for (int start = MAX_CHILDREN; start < children.size(); start += MAX_CHILDREN) {
            appendChunk(pageId, chunk(children, start));
        }
        return response;
    }

    private JsonNode children(String value) {
        DocumentationPathPolicy.requireText(value, JSON_CHILDREN);
        try {
            JsonNode node = json.readTree(value);
            if (node != null && node.isArray()) return node;
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("Invalid children JSON");
        }
        throw new IllegalArgumentException("children must be a JSON array");
    }

    private JsonNode titleProperties(String title) {
        if (title == null) throw new IllegalArgumentException("title is required");
        var properties = json.createObjectNode();
        properties.putObject(JSON_TITLE).putArray(JSON_TITLE).addObject()
                .putObject("text").put("content", title);
        return properties;
    }

    private JsonNode chunk(JsonNode children, int start) {
        var chunk = json.createArrayNode();
        for (int index = start; index < Math.min(start + MAX_CHILDREN, children.size()); index++) {
            chunk.add(children.get(index));
        }
        return chunk;
    }
}
