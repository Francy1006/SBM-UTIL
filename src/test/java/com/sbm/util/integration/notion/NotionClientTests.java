package com.sbm.util.integration.notion;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;

class NotionClientTests {

    @Test
    void retrievesPageBodyFromExpectedPath() {
        RestClient.Builder builder = RestClient.builder()
            .baseUrl("http://localhost");
        MockRestServiceServer mockServer = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();
        NotionClient notionClient = new NotionClient(restClient);
        String responseBody = "{\"object\":\"page\",\"id\":\"page-123\"}";

        mockServer.expect(requestTo("http://localhost/pages/page-123"))
                .andExpect(method(GET))
                .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

        String result = notionClient.retrievePage("page-123");

        assertThat(result).isEqualTo(responseBody);
        mockServer.verify();
    }

    @Test
    void retrievesBlockChildrenBodyFromExpectedPath() {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl("http://localhost");
        MockRestServiceServer mockServer = MockRestServiceServer.bindTo(builder).build();
        NotionClient notionClient = new NotionClient(builder.build());
        String responseBody = "{\"has_more\":false,\"results\":[{\"id\":\"heading_1\",\"type\":\"heading_1\"},{\"id\":\"heading_2\",\"type\":\"heading_2\"},{\"id\":\"paragraph\",\"type\":\"paragraph\"},{\"id\":\"heading_3\",\"type\":\"heading_3\"}]}";

        mockServer.expect(requestTo("http://localhost/blocks/page-123/children"))
                .andExpect(method(GET))
                .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

        String result = notionClient.retrieveBlockChildren("page-123");

        assertThat(result).isEqualTo(responseBody);
        mockServer.verify();
    }

    @Test
    void createsChildPageWithExpectedRequest() {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl("http://localhost");
        MockRestServiceServer mockServer = MockRestServiceServer.bindTo(builder).build();
        NotionClient notionClient = new NotionClient(builder.build());
        String responseBody = "{\"object\":\"page\",\"id\":\"created-page-123\"}";
        String childrenJson = "[{\"object\":\"block\",\"type\":\"paragraph\"}]";

        mockServer.expect(requestTo("http://localhost/pages"))
                .andExpect(method(POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().string(containsString("\"page_id\":\"parent-123\"")))
                .andExpect(content().string(containsString("\"content\":\"Child page\"")))
                .andExpect(content().string(containsString(childrenJson)))
                .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

            String result = notionClient.createChildPage("parent-123", "Child page", childrenJson);

        assertThat(result).isEqualTo(responseBody);
        mockServer.verify();
    }

    @Test
    void updatesAndReplacesBlockChildrenUsingExpectedOperations() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://localhost");
        MockRestServiceServer mockServer = MockRestServiceServer.bindTo(builder).build();
        NotionClient notionClient = new NotionClient(builder.build());

        mockServer.expect(requestTo("http://localhost/pages/page-123"))
                .andExpect(method(org.springframework.http.HttpMethod.PATCH))
                .andExpect(content().string(containsString("\"content\":\"Updated\"")))
                .andRespond(withSuccess("{\"id\":\"page-123\"}", MediaType.APPLICATION_JSON));
        mockServer.expect(requestTo("http://localhost/blocks/block-123"))
                .andExpect(method(org.springframework.http.HttpMethod.DELETE))
                .andRespond(withSuccess("{\"id\":\"block-123\"}", MediaType.APPLICATION_JSON));

        notionClient.updatePageTitle("page-123", "Updated");
        notionClient.appendBlockChildren("page-123", "[]");
        notionClient.deleteBlock("block-123");

        mockServer.verify();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {101, 205})
    void chunksCreateAndAppendWithoutLosingOrder(int count) {
        for (boolean create : new boolean[]{true, false}) {
            RestClient.Builder builder = RestClient.builder().baseUrl("http://localhost");
            MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
            NotionClient client = new NotionClient(builder.build());
            String markdown = java.util.stream.IntStream.range(0, count)
                    .mapToObj(i -> "Line " + i).collect(java.util.stream.Collectors.joining("\n"));
            String children = new NotionMarkdownBlockMapper().toChildrenJson(markdown);
            var json = new tools.jackson.databind.json.JsonMapper();
            var expected = json.readTree(children);
            for (int start = 0; start < count; start += 100) {
                int offset = start;
                boolean firstCreate = create && start == 0;
                server.expect(requestTo(firstCreate ? "http://localhost/pages"
                                : "http://localhost/blocks/page-123/children"))
                        .andExpect(method(firstCreate ? POST : org.springframework.http.HttpMethod.PATCH))
                        .andExpect(request -> {
                            var body = json.readTree(((org.springframework.mock.http.client.MockClientHttpRequest)
                                    request).getBodyAsString());
                            var blocks = body.path("children");
                            assertThat(blocks.size()).isEqualTo(Math.min(100, count - offset));
                            for (int i = 0; i < blocks.size(); i++) {
                                assertThat(blocks.get(i)).isEqualTo(expected.get(offset + i));
                            }
                        })
                        .andRespond(withSuccess("{\"id\":\"page-123\",\"results\":[]}",
                                MediaType.APPLICATION_JSON));
            }
            if (create) {
                assertThat(client.createChildPage("parent", "Title", children)).contains("page-123");
            } else {
                client.appendBlockChildren("page-123", children);
            }
            server.verify();
        }
    }

    @Test
    void retrievesAllThreePagesInOrder() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://localhost");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        for (int page = 0; page < 3; page++) {
            String suffix = page == 0 ? "" : "?start_cursor=cursor-" + page;
            server.expect(requestTo("http://localhost/blocks/page-123/children" + suffix))
                    .andExpect(method(GET))
                    .andRespond(withSuccess("{\"results\":[{\"id\":\"block-" + page
                            + "\"}],\"has_more\":" + (page < 2) + ",\"next_cursor\":"
                            + (page < 2 ? "\"cursor-" + (page + 1) + "\"" : "null") + "}",
                            MediaType.APPLICATION_JSON));
        }
        var result = new tools.jackson.databind.json.JsonMapper()
                .readTree(new NotionClient(builder.build()).retrieveBlockChildren("page-123"));
        assertThat(result.path("results").size()).isEqualTo(3);
        for (int i = 0; i < 3; i++) {
            assertThat(result.path("results").get(i).path("id").asString()).isEqualTo("block-" + i);
        }
        assertThat(result.path("has_more").asBoolean()).isFalse();
        assertThat(result.path("next_cursor").isNull()).isTrue();
        server.verify();
    }

    @Test
    void reconcilesExactCreationMarkerAcrossPagesIgnoringOtherDocuments() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://localhost");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://localhost/blocks/parent/children"))
                .andRespond(withSuccess("{\"results\":[{\"id\":\"other\",\"type\":\"child_page\","
                        + "\"child_page\":{\"title\":\"marker-other\"}}],"
                        + "\"has_more\":true,\"next_cursor\":\"next\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://localhost/blocks/parent/children?start_cursor=next"))
                .andRespond(withSuccess("{\"results\":[{\"id\":\"found\",\"type\":\"child_page\","
                        + "\"child_page\":{\"title\":\"marker\"}}],\"has_more\":false}", MediaType.APPLICATION_JSON));
        assertThat(new NotionClient(builder.build()).findChildPageByCreationMarker("parent", "marker"))
                .isEqualTo("found");
        server.verify();
    }

    @Test
    void refusesAmbiguousCreationMarkers() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://localhost");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://localhost/blocks/parent/children"))
                .andRespond(withSuccess("{\"has_more\":false,\"results\":["
                        + "{\"id\":\"one\",\"type\":\"child_page\",\"child_page\":{\"title\":\"marker\"}},"
                        + "{\"id\":\"two\",\"type\":\"child_page\",\"child_page\":{\"title\":\"marker\"}}]}",
                        MediaType.APPLICATION_JSON));
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                new NotionClient(builder.build()).findChildPageByCreationMarker("parent", "marker"))
                .hasMessageContaining("Multiple Notion pages");
        server.verify();
    }

    @Test
    void movesKnownPageAndRetrySkipsMoveWhenParentAlreadyMatches() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://localhost");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        NotionClient client = new NotionClient(builder.build());
        server.expect(requestTo("http://localhost/pages/doc")).andExpect(method(GET))
                .andRespond(withSuccess("{\"id\":\"doc\",\"parent\":{\"page_id\":\"old\"}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://localhost/pages/doc/move")).andExpect(method(POST))
                .andExpect(content().json("{\"parent\":{\"type\":\"page_id\",\"page_id\":\"new\"}}"))
                .andRespond(withSuccess("{\"id\":\"doc\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://localhost/pages/doc")).andExpect(method(GET))
                .andRespond(withSuccess("{\"id\":\"doc\",\"parent\":{\"page_id\":\"new\"}}", MediaType.APPLICATION_JSON));
        client.ensurePageParent("doc", "new");
        client.ensurePageParent("doc", "new");
        server.verify();
    }
}