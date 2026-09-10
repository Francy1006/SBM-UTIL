package com.sbm.util.integration.notion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NotionFinalQaTests {
    @TempDir Path documentation;

    @ParameterizedTest
    @ValueSource(strings = {"[]", "null", "{\"projectPageId\":\"parent\",\"documents\":[]}",
            "{\"projectPageId\":\"parent\",\"documents\":{\"a\":42}}",
            "{\"projectPageId\":null,\"documents\":{\"a\":\"page\"}}",
            "{\"projectPageId\":\"parent\",\"documents\":{},\"hashes\":[]}"})
    void malformedMappingIsRejectedWithoutRemoteSideEffects(String payload) throws Exception {
        var repository = new DocumentationNotionMappingRepository();
        Files.createDirectories(repository.mappingPath(documentation).getParent());
        Files.writeString(repository.mappingPath(documentation), payload);
        var notion = mock(NotionService.class);
        assertThatThrownBy(() -> service(notion, repository).synchronize("P", documentation))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(notion);
        assertThat(Files.readString(repository.mappingPath(documentation))).isEqualTo(payload);
    }

    @Test
    void concurrentSyncCannotOverwriteIntentOrDuplicateProjectCreation() throws Exception {
        var notion = mock(NotionService.class);
        when(notion.rootPageId()).thenReturn("root");
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(notion.createChildPage(anyString(), anyString(), anyString())).thenAnswer(call -> {
            entered.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("worker not released");
            return "{\"id\":\"project\"}";
        });
        when(notion.findChildPageByCreationMarker(anyString(), anyString())).thenReturn("project");
        var repository = new DocumentationNotionMappingRepository();
        try (var workers = Executors.newSingleThreadExecutor()) {
            var first = workers.submit(() -> service(notion, repository).synchronize("P", documentation));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> service(notion, new DocumentationNotionMappingRepository())
                        .synchronize("P", documentation))
                        .isInstanceOf(IllegalStateException.class).hasMessageContaining("already running");
                verify(notion, never()).findChildPageByCreationMarker(anyString(), anyString());
            } finally {
                release.countDown();
            }
            assertThat(first.get(5, TimeUnit.SECONDS).discovered()).isZero();
        }
        verify(notion, times(1)).createChildPage(anyString(), anyString(), anyString());
        assertThat(repository.load(documentation).projectPageId()).isEqualTo("project");
    }

    private NotionDocumentationSyncService service(NotionService notion, DocumentationNotionMappingRepository repository) {
        return new NotionDocumentationSyncService(notion, new NotionMarkdownBlockMapper(), repository,
                new DocumentationMarkdownReader(),
                new DocumentationPagePreparationService(new DocumentationPageFactory(new DocumentationStableIdGenerator())),
                new DocumentationPathPolicy(documentation));
    }

    @Test
    void fullHttpCyclePreservesHierarchyContentAndRetriesPartialLargeUpdate() throws Exception {
        var json = new tools.jackson.databind.json.JsonMapper();
        var pages = new java.util.LinkedHashMap<String, tools.jackson.databind.node.ObjectNode>();
        var content = new java.util.LinkedHashMap<String, tools.jackson.databind.node.ArrayNode>();
        var requests = new java.util.concurrent.atomic.AtomicInteger();
        var creates = new java.util.concurrent.atomic.AtomicInteger();
        var blockNumber = new java.util.concurrent.atomic.AtomicInteger();
        var failAfterFirstChunk = new java.util.concurrent.atomic.AtomicBoolean();
        var builder = org.springframework.web.client.RestClient.builder().baseUrl("http://localhost");
        var server = org.springframework.test.web.client.MockRestServiceServer.bindTo(builder).build();
        server.expect(org.springframework.test.web.client.ExpectedCount.manyTimes(), request -> {}).andRespond(request -> {
            requests.incrementAndGet();
            String path = request.getURI().getPath();
            String method = request.getMethod().name();
            var response = json.createObjectNode();
            if (method.equals("POST") && path.equals("/pages")) {
                creates.incrementAndGet();
                var body = (tools.jackson.databind.node.ObjectNode) json.readTree(
                        ((org.springframework.mock.http.client.MockClientHttpRequest) request).getBodyAsString());
                String id = "page-" + creates.get();
                String parent = body.path("parent").path("page_id").asString();
                assertThat(parent.equals("root") || pages.containsKey(parent)).isTrue();
                body.put("id", id);
                pages.put(id, body);
                content.put(id, json.createArrayNode());
                response.put("id", id);
            } else if (path.startsWith("/pages/") && method.equals("PATCH")) {
                String id = path.substring("/pages/".length());
                response.put("id", id);
            } else if (path.endsWith("/children")) {
                String id = path.split("/")[2];
                if (method.equals("GET")) {
                    int start = request.getURI().getQuery() == null ? 0
                            : Integer.parseInt(request.getURI().getQuery().split("=")[1]);
                    var all = content.get(id);
                    var result = response.putArray("results");
                    for (int i = start; i < Math.min(start + 100, all.size()); i++) result.add(all.get(i));
                    boolean more = start + 100 < all.size();
                    response.put("has_more", more);
                    if (more) response.put("next_cursor", Integer.toString(start + 100));
                    else response.putNull("next_cursor");
                } else {
                    if (failAfterFirstChunk.get() && content.get(id).size() == 100) {
                        failAfterFirstChunk.set(false);
                        return new org.springframework.mock.http.client.MockClientHttpResponse(
                                new byte[0], org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR);
                    }
                    var body = json.readTree(((org.springframework.mock.http.client.MockClientHttpRequest) request).getBodyAsString());
                    assertThat(body.path("children").size()).isLessThanOrEqualTo(100);
                    var result = response.putArray("results");
                    for (var child : body.path("children")) {
                        var block = ((tools.jackson.databind.node.ObjectNode) child).deepCopy();
                        block.put("id", "block-" + blockNumber.incrementAndGet());
                        content.get(id).add(block);
                        result.add(block);
                    }
                }
            } else if (method.equals("DELETE")) {
                String id = path.substring("/blocks/".length());
                content.values().forEach(blocks -> {
                    for (int i = blocks.size() - 1; i >= 0; i--) {
                        if (id.equals(blocks.get(i).path("id").asString())) blocks.remove(i);
                    }
                });
                response.put("id", id);
            } else throw new AssertionError(method + " " + path);
            var result = new org.springframework.mock.http.client.MockClientHttpResponse(
                    json.writeValueAsBytes(response), org.springframework.http.HttpStatus.OK);
            result.getHeaders().setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
            return result;
        });
        Files.createDirectories(documentation.resolve("a/b"));
        Files.writeString(documentation.resolve("README.md"), "");
        Files.writeString(documentation.resolve("one.md"), "Unicode 日本語 \"quote\" \\");
        Files.writeString(documentation.resolve("a/101.md"), "first\n".repeat(101));
        Files.writeString(documentation.resolve("a/b/205.md"), "original\n".repeat(205));
        var repository = new DocumentationNotionMappingRepository();
        var notion = new NotionService(new NotionClient(builder.build()), new NotionProperties("", "", "root"));
        var sync = service(notion, repository);
        assertThat(sync.synchronize("P", documentation)).isEqualTo(new DocumentationSyncResult("P", 4, 4, 0, 0));
        int initialRequests = requests.get();
        assertThat(sync.synchronize("P", documentation)).isEqualTo(new DocumentationSyncResult("P", 4, 0, 0, 4));
        assertThat(requests.get()).isEqualTo(initialRequests);
        Files.writeString(documentation.resolve("a/b/205.md"), "changed\n".repeat(205));
        failAfterFirstChunk.set(true);
        assertThatThrownBy(() -> sync.synchronize("P", documentation)).isInstanceOf(NotionClientException.class);
        assertThat(sync.synchronize("P", documentation)).isEqualTo(new DocumentationSyncResult("P", 4, 0, 1, 3));
        assertThat(creates.get()).isEqualTo(7);
        var mapping = repository.load(documentation);
        var mapper = new NotionMarkdownBlockMapper();
        for (var source : new DocumentationMarkdownReader().discover("P", documentation)) {
            String stable = new DocumentationStableIdGenerator().generate("P", source.sourcePath());
            var actual = content.get(mapping.documents().get(stable)).deepCopy();
            actual.forEach(block -> ((tools.jackson.databind.node.ObjectNode) block).remove("id"));
            assertThat(actual).isEqualTo(json.readTree(mapper.toChildrenJson(source.markdown())));
            assertThat(pages.get(mapping.documents().get(stable)).path("parent").path("page_id").asString())
                    .isEqualTo(mapping.parents().get(stable));
        }
        assertThat(mapping.pendingCreates()).isEmpty();
        server.verify();
    }
}
