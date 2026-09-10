package com.sbm.util.integration.notion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class NotionSyncChunkRecoveryTests {
    @TempDir Path documentation;

    @Test
    void retriesFailedSecondChunkUsingDurablePageIdAndReplacesPartialContent() throws Exception {
        var repository = new DocumentationNotionMappingRepository();
        repository.save(documentation, new DocumentationNotionMapping("parent", Map.of()));
        String markdown = IntStream.range(0, 205).mapToObj(i -> "Line " + i).collect(Collectors.joining("\n"));
        Files.writeString(documentation.resolve("guide.md"), markdown);
        String stable = new DocumentationStableIdGenerator().generate("project", "guide.md");
        RestClient.Builder builder = RestClient.builder().baseUrl("http://localhost");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        var service = new NotionDocumentationSyncService(
                new NotionService(new NotionClient(builder.build()), new NotionProperties("", "", "root")),
                new NotionMarkdownBlockMapper(), repository, new DocumentationMarkdownReader(),
                new DocumentationPagePreparationService(new DocumentationPageFactory(new DocumentationStableIdGenerator())), new DocumentationPathPolicy(Path.of(System.getProperty("java.io.tmpdir"))));
        AtomicInteger creations = new AtomicInteger();
        server.expect(requestTo("http://localhost/pages")).andExpect(method(HttpMethod.POST))
                .andExpect(request -> {
                    creations.incrementAndGet();
                    assertThat(repository.load(documentation).pendingCreates()).containsKey(stable);
                }).andRespond(withSuccess("{\"id\":\"doc\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://localhost/pages/doc")).andExpect(method(HttpMethod.PATCH))
                .andExpect(request -> assertThat(repository.load(documentation).documents()).containsEntry(stable, "doc"))
                .andRespond(withSuccess("{\"id\":\"page-123\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://localhost/blocks/doc/children")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"has_more\":false,\"results\":[]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://localhost/blocks/doc/children")).andExpect(method(HttpMethod.PATCH))
                .andRespond(withSuccess("{\"has_more\":false,\"results\":[]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://localhost/blocks/doc/children")).andExpect(method(HttpMethod.PATCH))
                .andRespond(withServerError());
        assertThatThrownBy(() -> service.synchronize("project", documentation))
                .isInstanceOf(NotionClientException.class);
        server.verify();
        assertThat(repository.load(documentation).documents()).containsEntry(stable, "doc");
        assertThat(repository.load(documentation).hashes()).doesNotContainKey(stable);
        server.reset();

        server.expect(requestTo("http://localhost/pages/doc")).andExpect(method(HttpMethod.PATCH))
                .andRespond(withSuccess("{\"id\":\"page-123\"}", MediaType.APPLICATION_JSON));
        String partial = IntStream.range(0, 100).mapToObj(i -> "{\"id\":\"block-" + i + "\"}")
                .collect(Collectors.joining(",", "{\"has_more\":false,\"results\":[", "]}"));
        server.expect(requestTo("http://localhost/blocks/doc/children")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(partial, MediaType.APPLICATION_JSON));
        for (int i = 0; i < 100; i++) {
            server.expect(requestTo("http://localhost/blocks/block-" + i)).andExpect(method(HttpMethod.DELETE))
                    .andRespond(withSuccess("{\"id\":\"block-123\"}", MediaType.APPLICATION_JSON));
        }
        var json = new JsonMapper();
        var expected = json.readTree(new NotionMarkdownBlockMapper().toChildrenJson(markdown));
        for (int start = 0; start < 205; start += 100) {
            int offset = start;
            server.expect(requestTo("http://localhost/blocks/doc/children")).andExpect(method(HttpMethod.PATCH))
                    .andExpect(request -> {
                        var blocks = json.readTree(((org.springframework.mock.http.client.MockClientHttpRequest)
                                request).getBodyAsString()).path("children");
                        assertThat(blocks.size()).isEqualTo(Math.min(100, 205 - offset));
                        for (int i = 0; i < blocks.size(); i++) {
                            assertThat(blocks.get(i)).isEqualTo(expected.get(offset + i));
                        }
                        assertThat(repository.load(documentation).hashes()).doesNotContainKey(stable);
                    }).andRespond(withSuccess("{\"has_more\":false,\"results\":[]}", MediaType.APPLICATION_JSON));
        }
        assertThat(service.synchronize("project", documentation).updated()).isEqualTo(1);
        assertThat(repository.load(documentation).hashes().get(stable)).matches("[0-9a-f]{64}");
        assertThat(service.synchronize("project", documentation).unchanged()).isEqualTo(1);
        assertThat(creations.get()).isEqualTo(1);
        server.verify();
    }
}
