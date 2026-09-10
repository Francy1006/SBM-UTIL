package com.sbm.util.integration.notion;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import java.net.http.HttpClient;

@Configuration
@EnableConfigurationProperties(NotionProperties.class)
public class NotionConfig {

	@Bean
	RestClient notionRestClient(NotionProperties properties) {
		return RestClient.builder()
				.baseUrl("https://api.notion.com/v1")
                .requestFactory(notionRequestFactory(properties))
				.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiToken())
				.defaultHeader("Notion-Version", properties.apiVersion())
				.defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
				.build();
	}

    JdkClientHttpRequestFactory notionRequestFactory(NotionProperties properties) {
        var http = HttpClient.newBuilder().connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER).build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(properties.requestTimeout());
        return factory;
    }

	@Bean
	NotionClient notionClient(RestClient restClient) {
		return new NotionClient(restClient);
	}
}