package com.sbm.util.integration.notion;

import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.ResourceAccessException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;

public class NotionClient {
    private static final int MAX_CHILDREN = 100;
    private static final Map<String, String> ERRORS = Map.of(
            "unauthorized", "Authentication rejected",
            "restricted_resource", "Access denied",
            "object_not_found", "Resource not found",
            "validation_error", "Request validation failed",
            "invalid_json", "Request JSON rejected",
            "rate_limited", "Rate limit exceeded",
            "conflict_error", "Remote conflict",
            "internal_server_error", "Remote internal error",
            "service_unavailable", "Remote service unavailable",
            "gateway_timeout", "Remote gateway timed out");
    private final RestClient restClient;
    private final JsonMapper json = new JsonMapper();

    public NotionClient(RestClient restClient) { this.restClient = restClient; }

    private String send(String operation, RestClient.RequestHeadersSpec<?> request) {
        try {
            return request.exchange((httpRequest, response) -> {
                int status = response.getStatusCode().value();
                if (status < 200 || status >= 300) {
                    // Never echo arbitrary remote messages: they may contain submitted secrets.
                    String code = "http_error";
                    try {
                        JsonNode error = json.readTree(response.getBody().readNBytes(8192));
                        if (error != null && ERRORS.containsKey(error.path("code").asString(""))) {
                            code = error.path("code").asString();
                        }
                    } catch (JacksonException | java.io.IOException ignored) {
                        // Status and operation remain useful even for HTML/malformed error bodies.
                    }
                    throw new NotionClientException(status, operation, code,
                            ERRORS.getOrDefault(code, "Remote request failed"));
                }
                return new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
            });
        } catch (ResourceAccessException exception) {
            Throwable cause = exception;
            boolean timedOut = false;
            while (cause != null) {
                timedOut |= cause instanceof java.net.http.HttpTimeoutException
                        || cause instanceof java.net.SocketTimeoutException;
                cause = cause.getCause();
            }
            throw new NotionClientException(null, operation, timedOut ? "timeout" : "transport_error",
                    timedOut ? "Request timed out; remote outcome may be uncertain"
                            : "Connection or response failed; remote outcome may be uncertain");
        }
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
        String response = send("retrievePage", restClient.get().uri("/pages/{pageId}", pageId));
        requiredId(object(response, "retrievePage"), "retrievePage");
        return response;
    }

    public String retrieveBlockChildren(String pageId) {
        id(pageId);
        String operation = "retrieveBlockChildren";
        String response = send(operation, restClient.get().uri("/blocks/{pageId}/children", pageId));
        ObjectNode payload = list(response, operation, true);
        if (!payload.path("has_more").asBoolean()) return response;
        var results = json.createArrayNode();
        payload.path("results").forEach(results::add);
        ObjectNode current = payload;
        var cursors = new HashSet<String>();
        while (current.path("has_more").asBoolean()) {
            String cursor = current.path("next_cursor").asString();
            if (!cursors.add(cursor)) throw invalid(operation);
            current = list(send(operation, restClient.get().uri(builder -> builder
                    .path("/blocks/{pageId}/children").queryParam("start_cursor", cursor).build(pageId))),
                    operation, true);
            current.path("results").forEach(results::add);
        }
        payload.set("results", results);
        payload.put("has_more", false);
        payload.putNull("next_cursor");
        return json.writeValueAsString(payload);
    }

    private ObjectNode list(String response, String operation, boolean paginated) {
        ObjectNode payload = object(response, operation);
        if (!payload.path("results").isArray()) throw invalid(operation);
        for (JsonNode block : payload.path("results")) requiredId(block, operation);
        if (paginated && (!payload.path("has_more").isBoolean()
                || (payload.path("has_more").asBoolean()
                && (!payload.path("next_cursor").isString() || payload.path("next_cursor").asString().isBlank())))) {
            throw invalid(operation);
        }
        return payload;
    }

    public String findChildPageByCreationMarker(String parentPageId, String marker) {
        DocumentationPathPolicy.requireText(marker, "creation marker");
        JsonNode children = object(retrieveBlockChildren(parentPageId), "findChildPage").path("results");
        String pageId = null;
        for (JsonNode child : children) {
            if ("child_page".equals(child.path("type").asString())) {
                if (!child.path("child_page").path("title").isString()) throw invalid("findChildPage");
                if (marker.equals(child.path("child_page").path("title").asString())) {
                    if (pageId != null) {
                        throw new NotionClientException(null, "findChildPage", "ambiguous_creation",
                                "Multiple Notion pages match creation marker");
                    }
                    pageId = requiredId(child, "findChildPage");
                }
            }
        }
        return pageId;
    }

    public void ensurePageParent(String pageId, String parentPageId) {
        id(parentPageId);
        JsonNode page = object(retrievePage(pageId), "ensurePageParent");
        JsonNode parent = page.path("parent").path("page_id");
        if (!parent.isString() || parent.asString().isBlank()) throw invalid("ensurePageParent");
        if (parent.asString().replace("-", "").equals(parentPageId.replace("-", ""))) return;
        var body = json.createObjectNode();
        body.putObject("parent").put("type", "page_id").put("page_id", parentPageId);
        String response = send("movePage", restClient.post().uri("/pages/{pageId}/move", pageId)
                .contentType(MediaType.APPLICATION_JSON).body(json.writeValueAsString(body)));
        requiredId(object(response, "movePage"), "movePage");
    }

    public String updatePageTitle(String pageId, String title) {
        id(pageId);
        var body = json.createObjectNode();
        body.set("properties", titleProperties(title));
        String response = send("updatePageTitle", restClient.patch().uri("/pages/{pageId}", pageId)
                .contentType(MediaType.APPLICATION_JSON).body(json.writeValueAsString(body)));
        requiredId(object(response, "updatePageTitle"), "updatePageTitle");
        return response;
    }

    public void deleteBlock(String blockId) {
        id(blockId);
        String response = send("deleteBlock", restClient.delete().uri("/blocks/{blockId}", blockId));
        requiredId(object(response, "deleteBlock"), "deleteBlock");
    }

    public String appendBlockChildren(String pageId, String childrenJson) {
        id(pageId);
        JsonNode children = children(childrenJson);
        var results = json.createArrayNode();
        String response = null;
        for (int start = 0; start < children.size(); start += MAX_CHILDREN) {
            response = appendChunk(pageId, chunk(children, start));
            object(response, "appendBlockChildren").path("results").forEach(results::add);
        }
        if (response != null && children.size() <= MAX_CHILDREN) return response;
        var payload = json.createObjectNode();
        payload.set("results", results);
        payload.put("has_more", false);
        payload.putNull("next_cursor");
        return json.writeValueAsString(payload);
    }

    private String appendChunk(String pageId, JsonNode children) {
        var body = json.createObjectNode();
        body.set("children", children);
        String response = send("appendBlockChildren", restClient.patch().uri("/blocks/{pageId}/children", pageId)
                .contentType(MediaType.APPLICATION_JSON).body(json.writeValueAsString(body)));
        list(response, "appendBlockChildren", false);
        return response;
    }

    public String createChildPage(String parentPageId, String title, String childrenJson) {
        id(parentPageId);
        JsonNode children = children(childrenJson);
        var body = json.createObjectNode();
        body.putObject("parent").put("page_id", parentPageId);
        body.set("properties", titleProperties(title));
        body.set("children", chunk(children, 0));
        String response = send("createChildPage", restClient.post().uri("/pages")
                .contentType(MediaType.APPLICATION_JSON).body(json.writeValueAsString(body)));
        String pageId = requiredId(object(response, "createChildPage"), "createChildPage");
        for (int start = MAX_CHILDREN; start < children.size(); start += MAX_CHILDREN) {
            appendChunk(pageId, chunk(children, start));
        }
        return response;
    }

    private JsonNode children(String value) {
        DocumentationPathPolicy.requireText(value, "children");
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
        properties.putObject("title").putArray("title").addObject()
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
