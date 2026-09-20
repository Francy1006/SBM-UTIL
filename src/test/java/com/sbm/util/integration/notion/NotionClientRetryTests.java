package com.sbm.util.integration.notion;

import java.net.SocketTimeoutException;
import java.io.IOException;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class NotionClientRetryTests {
    private final RestClient.Builder builder = RestClient.builder().baseUrl("http://localhost");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final List<Long> delays = new ArrayList<>();
    private final NotionClient client = new NotionClient(builder.build(), delays::add);

    private void failure(HttpMethod method, String path, int status, String retryAfter) {
        var response = withStatus(HttpStatusCode.valueOf(status))
                .body("{\"code\":\"untrusted-SECRET\",\"message\":\"SECRET\"}");
        if (retryAfter != null) response.header("Retry-After", retryAfter);
        server.expect(requestTo("http://localhost" + path)).andExpect(method(method)).andRespond(response);
    }

    private void success(HttpMethod method, String path) {
        server.expect(requestTo("http://localhost" + path)).andExpect(method(method))
                .andRespond(withSuccess("{\"id\":\"item\"}", MediaType.APPLICATION_JSON));
    }

    @ParameterizedTest
    @ValueSource(ints = {429, 529})
    void respectsRetryAfterAndSucceeds(int status) {
        failure(HttpMethod.GET, "/pages/item", status, "2");
        success(HttpMethod.GET, "/pages/item");
        assertThat(client.retrievePage("item")).contains("item");
        assertThat(delays).containsExactly(2000L);
        server.verify();
    }

    @Test
    void acceptsHttpDateRetryAfter() {
        failure(HttpMethod.GET, "/pages/item", 429,
                ZonedDateTime.now(java.time.ZoneOffset.UTC).plusSeconds(30)
                        .format(DateTimeFormatter.RFC_1123_DATE_TIME));
        success(HttpMethod.GET, "/pages/item");
        client.retrievePage("item");
        assertThat(delays.getFirst()).isBetween(25000L, 30000L);
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(ints = {429, 529, 500, 502, 503, 504})
    void retriesGetAndDeleteWithBackoff(int status) {
        for (HttpMethod method : List.of(HttpMethod.GET, HttpMethod.DELETE)) {
            String path = method == HttpMethod.GET ? "/pages/item" : "/blocks/item";
            failure(method, path, status, null);
            success(method, path);
        }
        client.retrievePage("item");
        client.deleteBlock("item");
        assertThat(delays).hasSize(2).allSatisfy(delay -> assertThat(delay).isBetween(250L, 500L));
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(ints = {429, 529, 500, 502, 503, 504})
    void neverRetriesPostOrPatch(int status) {
        failure(HttpMethod.POST, "/pages", status, "1");
        failure(HttpMethod.PATCH, "/blocks/item/children", status, "1");
        assertThatThrownBy(() -> client.createChildPage("parent", "title", "[]"))
                .isInstanceOf(NotionClientException.class);
        assertThatThrownBy(() -> client.appendBlockChildren("item", "[{\"object\":\"block\"}]"))
                .isInstanceOf(NotionClientException.class);
        assertThat(delays).isEmpty();
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(ints = {429, 529, 503})
    void exhaustionIsBoundedAndSanitized(int status) {
        for (int i = 0; i < 3; i++) failure(HttpMethod.DELETE, "/blocks/item", status, "SECRET");
        var error = catchThrowableOfType(NotionClientException.class, () -> client.deleteBlock("item"));
        assertThat(error.status()).isEqualTo(status);
        assertThat(error.operation()).isEqualTo("deleteBlock");
        assertThat(error.code()).isEqualTo(status == 529 ? "service_overload" : "http_error");
        assertThat(error).hasMessageNotContaining("SECRET").hasNoCause();
        assertThat(delays).hasSize(2);
        assertThat(delays.get(0)).isBetween(250L, 500L);
        assertThat(delays.get(1)).isBetween(500L, 1000L);
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void deleteTransportFailureCanBeRetriedSafely(boolean timeout) {
        server.expect(requestTo("http://localhost/blocks/item")).andExpect(method(HttpMethod.DELETE))
                .andRespond(withException(timeout ? new SocketTimeoutException("SECRET") : new IOException("SECRET")));
        success(HttpMethod.DELETE, "/blocks/item");
        client.deleteBlock("item");
        assertThat(delays).hasSize(1);
        server.verify();
    }

    @Test
    void exhaustedDeleteTimeoutRemainsUncertainAndSanitized() {
        for (int i = 0; i < 3; i++) {
            server.expect(requestTo("http://localhost/blocks/item"))
                    .andRespond(withException(new SocketTimeoutException("SECRET")));
        }
        var error = catchThrowableOfType(NotionClientException.class, () -> client.deleteBlock("item"));
        assertThat(error.code()).isEqualTo("timeout");
        assertThat(error.status()).isNull();
        assertThat(error).hasMessageContaining("remote outcome may be uncertain")
                .hasMessageNotContaining("SECRET").hasNoCause();
        assertThat(delays).hasSize(2);
        server.verify();
    }

    @Test
    void interruptionStopsRetriesAndPreservesInterruptFlag() {
        var interruptedClient = new NotionClient(builder.build(), millis -> { throw new InterruptedException("SECRET"); });
        failure(HttpMethod.DELETE, "/blocks/item", 503, null);
        try {
            var error = catchThrowableOfType(NotionClientException.class, () -> interruptedClient.deleteBlock("item"));
            assertThat(error.code()).isEqualTo("interrupted");
            assertThat(error).hasMessageNotContaining("SECRET").hasNoCause();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
        server.verify();
    }
}
