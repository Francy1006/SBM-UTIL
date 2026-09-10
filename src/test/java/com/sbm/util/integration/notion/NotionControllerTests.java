package com.sbm.util.integration.notion;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Path;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "sbm.security.service-token=test-token")
@AutoConfigureMockMvc
class NotionControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private NotionService notionService;

    @MockitoBean
    private NotionDocumentationSyncService notionDocumentationSyncService;

    @Test
    void rejectsRequestWithoutServiceToken() throws Exception {
        mockMvc.perform(get("/api/notion/root-page"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void returnsRootPageWithValidServiceToken() throws Exception {
        String responseBody = "{\"object\":\"page\",\"id\":\"root-123\"}";
        when(notionService.retrieveRootPage()).thenReturn(responseBody);

        mockMvc.perform(get("/api/notion/root-page")
                        .header("X-SBM-Service-Token", "test-token"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(content().string(responseBody));
    }

    @Test
    void rejectsSyncWithoutServiceToken() throws Exception {
        mockMvc.perform(post("/api/notion/documentation/sync")
                    .contentType(APPLICATION_JSON)
                    .content("{\"project\":\"SBM-UTIL\",\"documentationPath\":\"/tmp/Documentation\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void synchronizesDocumentationWithValidServiceToken() throws Exception {
        DocumentationSyncResult result = new DocumentationSyncResult("SBM-UTIL", 2, 1, 1, 0);
        when(notionDocumentationSyncService.synchronize(eq("SBM-UTIL"), eq(Path.of("/tmp/Documentation"))))
                .thenReturn(result);

        mockMvc.perform(post("/api/notion/documentation/sync")
                    .header("X-SBM-Service-Token", "test-token")
                    .contentType(APPLICATION_JSON)
                    .content("{\"project\":\"SBM-UTIL\",\"documentationPath\":\"/tmp/Documentation\"}"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                .andExpect(content().json("{\"project\":\"SBM-UTIL\",\"discovered\":2,\"created\":1,\"updated\":1,\"unchanged\":0}"));

        verify(notionDocumentationSyncService).synchronize("SBM-UTIL", Path.of("/tmp/Documentation"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "{}", "{\"project\":null,\"documentationPath\":\".\"}",
            "{\"project\":\" \",\"documentationPath\":\".\"}",
            "{\"project\":\"P\",\"documentationPath\":null}",
            "{\"project\":\"P\",\"documentationPath\":\"\"}"
    })
    void rejectsMissingSyncParametersBeforeService(String payload) throws Exception {
        mockMvc.perform(post("/api/notion/documentation/sync")
                        .header("X-SBM-Service-Token", "test-token")
                        .contentType(APPLICATION_JSON).content(payload))
                .andExpect(status().isBadRequest());
        org.mockito.Mockito.verifyNoInteractions(notionDocumentationSyncService);
    }
}