package com.rxda.nacoscseconfigbridge.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Verifies the login contract that Nacos clients depend on.
 */
class NacosAuthControllerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new NacosAuthController())
            .build();

    @Test
    void clientLoginPathIssuesUsableAccessToken() throws Exception {
        String body = mockMvc.perform(post("/nacos/v1/auth/users/login")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("username", "nacos")
                        .param("password", "nacos-secret"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.username").value("nacos"))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        JsonNode response = mapper.readTree(body);
        JsonNode ttl = response.get("tokenTtl");
        // Nacos clients call Long.parseLong on tokenTtl and fail the login
        // when the field is missing or not numeric.
        assertThat(ttl).isNotNull();
        assertThat(ttl.asText()).matches("\\d+");
        assertThat(response.get("accessToken").asText()).isNotBlank();
    }

    @Test
    void alternateLoginPathUsedByOtherToolingIsAlsoServed() throws Exception {
        mockMvc.perform(post("/v1/auth/login")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("username", "nacos")
                        .param("password", "nacos-secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty());
    }

    @Test
    void loginWithoutCredentialsStillAnswersSoNoClientBreaks() throws Exception {
        mockMvc.perform(post("/nacos/v1/auth/users/login"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.username").value(""));
    }
}
