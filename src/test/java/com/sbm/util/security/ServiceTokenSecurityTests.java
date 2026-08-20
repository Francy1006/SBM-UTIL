package com.sbm.util.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "sbm.security.service-token=test-token")
@AutoConfigureMockMvc
class ServiceTokenSecurityTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void rejectsRequestWithoutServiceToken() throws Exception {
        mockMvc.perform(get("/api/test"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void allowsRequestWithValidServiceToken() throws Exception {
        mockMvc.perform(get("/api/test")
                        .header("X-SBM-Service-Token", "test-token"))
                .andExpect(status().isNotFound());
    }
}