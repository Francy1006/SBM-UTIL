package com.sbm.util.integration.notion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.mockito.Mockito.*;

class NotionHardeningTests {
    @TempDir Path workspace;

    @Test
    void rejectsTraversalAbsoluteEscapeAndAllowsNormalizedContainedPaths() throws Exception {
        Path root = Files.createDirectory(workspace.resolve("docs"));
        Files.createDirectory(root.resolve("sub"));
        var policy = new DocumentationPathPolicy(root);
        assertThatThrownBy(() -> policy.validate("P", Path.of("../"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.validate("P", workspace)).isInstanceOf(IllegalArgumentException.class);
        assertThat(policy.validate("P", Path.of("sub/.."))).isEqualTo(root.toRealPath());
        assertThat(policy.validate("P", root.resolve("sub/.."))).isEqualTo(root.toRealPath());
    }

    @Test
    void symlinksCannotEscapeThroughRequestedPathReaderOrMapping() throws Exception {
        Path root = Files.createDirectory(workspace.resolve("docs"));
        Path outside = Files.createDirectory(workspace.resolve("outside"));
        Files.writeString(outside.resolve("secret.md"), "secret");
        Files.writeString(root.resolve("safe.md"), "safe");
        Files.createSymbolicLink(root.resolve("link"), outside);
        Files.createSymbolicLink(root.resolve("secret.md"), outside.resolve("secret.md"));
        var policy = new DocumentationPathPolicy(root);
        assertThatThrownBy(() -> policy.validate("P", Path.of("link"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.validate("P", Path.of("link/.."))).isInstanceOf(IllegalArgumentException.class);
        assertThat(new DocumentationMarkdownReader().discover("P", root))
                .extracting(DocumentationMarkdownReader.DocumentationSource::sourcePath).containsExactly("safe.md");
        Files.createSymbolicLink(root.resolve(".sbm"), outside);
        var repo = new DocumentationNotionMappingRepository();
        assertThatThrownBy(() -> repo.load(root)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repo.save(root, DocumentationNotionMapping.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        Files.delete(root.resolve(".sbm"));
        Files.createDirectory(root.resolve(".sbm"));
        Files.createSymbolicLink(root.resolve(".sbm/notion-sync-map.json"), outside.resolve("secret.md"));
        assertThatThrownBy(() -> repo.load(root)).isInstanceOf(IllegalArgumentException.class);
        assertThat(Files.readString(outside.resolve("secret.md"))).isEqualTo("secret");
    }

    @Test
    void invalidRequiredInputsFailBeforeRepositoryOrRemoteCalls() {
        var notion = mock(NotionService.class);
        var repo = mock(DocumentationNotionMappingRepository.class);
        var service = new NotionDocumentationSyncService(notion, new NotionMarkdownBlockMapper(), repo,
                new DocumentationMarkdownReader(),
                new DocumentationPagePreparationService(new DocumentationPageFactory(new DocumentationStableIdGenerator())),
                new DocumentationPathPolicy(workspace));
        for (String project : new String[]{null, "", "  ", "a\nb"}) {
            assertThatThrownBy(() -> service.synchronize(project, workspace)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> service.synchronize("P", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.synchronize("P", Path.of(""))).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(notion, repo);
        var client = new NotionClient(RestClient.create());
        assertThatThrownBy(() -> client.createChildPage("", "title", "[]")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.retrievePage(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {302, 400, 401, 403, 429, 500, 503})
    void httpErrorsPreserveStatusAndOperationWithoutLeakingSecrets(int status) {
        var builder = RestClient.builder().baseUrl("http://localhost").defaultHeader("Authorization", "Bearer TOP-SECRET");
        var server = MockRestServiceServer.bindTo(builder).build();
        var client = new NotionClient(builder.build());
        server.expect(requestTo("http://localhost/pages/page"))
                .andRespond(withStatus(HttpStatusCode.valueOf(status)).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"validation_error\",\"message\":\"Authorization: TOP-SECRET X-SBM-Service-Token: OTHER-SECRET\"}"));
        var error = catchThrowableOfType(NotionClientException.class, () -> client.retrievePage("page"));
        assertThat(error.status()).isEqualTo(status);
        assertThat(error.operation()).isEqualTo("retrievePage");
        assertThat(error.code()).isEqualTo("validation_error");
        assertThat(error.getMessage()).doesNotContain("TOP-SECRET", "OTHER-SECRET", "Authorization", "X-SBM");
        assertThat(error.getCause()).isNull();
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"not JSON TOP-SECRET", "[]", "null", "{}", "{\"id\":null}", "{\"id\":123}"})
    void invalidCreateResponseIsControlled(String response) {
        var builder = RestClient.builder().baseUrl("http://localhost");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://localhost/pages"))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
        var client = new NotionClient(builder.build());
        var error = catchThrowableOfType(NotionClientException.class, () -> client.createChildPage("parent", "title", "[]"));
        assertThat(error.code()).isEqualTo("invalid_response");
        assertThat(error.getMessage()).doesNotContain("TOP-SECRET");
        assertThat(error.getCause()).isNull();
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"results\":[]}", "{\"results\":{},\"has_more\":false}",
            "{\"results\":[{}],\"has_more\":false}", "{\"results\":[],\"has_more\":true,\"next_cursor\":null}"})
    void incompleteBlockListsAreRejected(String response) {
        var builder = RestClient.builder().baseUrl("http://localhost");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://localhost/blocks/page/children"))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> new NotionClient(builder.build()).retrieveBlockChildren("page"))
                .isInstanceOf(NotionClientException.class);
        server.verify();
    }

    @Test
    void serializesExternalTitleAndMarkdownWithoutChangingOrdinaryPayload() {
        String title = "\"quoted\" \\ / \n Español 日本語";
        String markdown = "# \"quoted\" \\ / 日本語\nline\t\u0001";
        var mapper = new NotionMarkdownBlockMapper();
        var json = new JsonMapper();
        String children = mapper.toChildrenJson(markdown);
        assertThat(json.readTree(children).get(1).path("paragraph").path("rich_text").get(0)
                .path("text").path("content").asString()).isEqualTo("line\t\u0001");
        assertThat(mapper.toChildrenJson("Body")).isEqualTo(
                "[{\"object\":\"block\",\"type\":\"paragraph\",\"paragraph\":{\"rich_text\":[{\"type\":\"text\",\"text\":{\"content\":\"Body\"}}]}}]");
        var builder = RestClient.builder().baseUrl("http://localhost");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://localhost/pages")).andExpect(request -> {
            var body = json.readTree(((org.springframework.mock.http.client.MockClientHttpRequest) request).getBodyAsString());
            assertThat(body.path("properties").path("title").path("title").get(0).path("text").path("content").asString())
                    .isEqualTo(title);
            assertThat(body.path("children")).isEqualTo(json.readTree(children));
        }).andRespond(withSuccess("{\"id\":\"page\"}", MediaType.APPLICATION_JSON));
        new NotionClient(builder.build()).createChildPage("parent", title, children);
        server.verify();
    }

    @Test
    void timeoutOutcomeUsesExistingReconciliationWithoutAnotherCreate() throws Exception {
        Files.writeString(workspace.resolve("guide.md"), "# Guide");
        var repo = new DocumentationNotionMappingRepository();
        repo.save(workspace, new DocumentationNotionMapping("parent", Map.of()));
        var builder = RestClient.builder().baseUrl("http://localhost");
        var server = MockRestServiceServer.bindTo(builder).build();
        var notion = new NotionService(new NotionClient(builder.build()), new NotionProperties("", "", "root"));
        var service = new NotionDocumentationSyncService(notion, new NotionMarkdownBlockMapper(), repo,
                new DocumentationMarkdownReader(),
                new DocumentationPagePreparationService(new DocumentationPageFactory(new DocumentationStableIdGenerator())),
                new DocumentationPathPolicy(workspace));
        AtomicInteger creates = new AtomicInteger();
        server.expect(requestTo("http://localhost/pages")).andExpect(request -> creates.incrementAndGet())
                .andRespond(withException(new java.net.SocketTimeoutException("TOP-SECRET")));
        assertThatThrownBy(() -> service.synchronize("P", workspace)).isInstanceOf(NotionClientException.class)
                .hasMessageNotContaining("TOP-SECRET");
        server.verify();
        server.reset();
        String marker = repo.load(workspace).pendingCreates().values().iterator().next();
        var json = new JsonMapper();
        String found = json.writeValueAsString(Map.of("has_more", false, "results", java.util.List.of(
                Map.of("id", "doc", "type", "child_page", "child_page", Map.of("title", marker)))));
        server.expect(requestTo("http://localhost/blocks/parent/children")).andRespond(withSuccess(found, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://localhost/pages/doc")).andRespond(withSuccess("{\"id\":\"doc\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://localhost/blocks/doc/children"))
                .andRespond(withSuccess("{\"results\":[],\"has_more\":false}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://localhost/blocks/doc/children"))
                .andRespond(withSuccess("{\"results\":[{\"id\":\"block\"}]}", MediaType.APPLICATION_JSON));
        assertThat(service.synchronize("P", workspace).created()).isEqualTo(1);
        assertThat(service.synchronize("P", workspace).unchanged()).isEqualTo(1);
        assertThat(creates.get()).isEqualTo(1);
        server.verify();
    }

    @Test
    void configuredRequestTimeoutActuallyBoundsWaitingForResponse() throws Exception {
        var properties = new NotionProperties("TOP-SECRET", "", "", workspace,
                Duration.ofMillis(100), Duration.ofMillis(150));
        assertThat(properties.toString()).doesNotContain("TOP-SECRET");
        var factory = new NotionConfig().notionRequestFactory(properties);
        var http = (java.net.http.HttpClient) org.springframework.test.util.ReflectionTestUtils.getField(factory, "httpClient");
        assertThat(http.connectTimeout()).contains(Duration.ofMillis(100));
        try (var socket = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            var builder = RestClient.builder().baseUrl("http://localhost:" + socket.getLocalPort()).requestFactory(factory);
            long started = System.nanoTime();
            assertThatThrownBy(() -> new NotionClient(builder.build()).retrievePage("page"))
                    .isInstanceOf(NotionClientException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
        }
    }
}
