package com.rxda.nacoscseconfigbridge.web;

import java.util.Optional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rxda.nacoscseconfigbridge.auth.NacosAuthService;
import com.rxda.nacoscseconfigbridge.config.NacosAuthProperties;
import com.rxda.nacoscseconfigbridge.cse.KieConfigStore;
import com.rxda.nacoscseconfigbridge.nacos.NacosConfigKey;

class NacosAuthWebTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private NacosAuthProperties properties;
    private NacosAuthService authService;
    private MockMvc mockMvc;
    private MockMvc loginMockMvc;

    @BeforeEach
    void setUp() throws Exception {
        properties = new NacosAuthProperties();
        properties.setEnabled(true);
        properties.setUsername("bridge-user");
        properties.setPassword("bridge-password");
        authService = new NacosAuthService(properties);
        authService.afterPropertiesSet();

        NacosAuthController controller = new NacosAuthController(authService, properties);
        NacosAuthInterceptor interceptor = new NacosAuthInterceptor(authService, objectMapper);
        loginMockMvc = MockMvcBuilders.standaloneSetup(controller)
                .build();

        KieConfigStore configStore = new KieConfigStore() {
            @Override
            public ReadResult read(NacosConfigKey key, String revision, boolean longPolling) {
                return new ReadResult(true, "test-revision", Optional.of("ok"));
            }
        };
        mockMvc = MockMvcBuilders.standaloneSetup(new NacosConfigController(configStore))
                .addInterceptors(interceptor)
                .build();
    }

    @AfterEach
    void resetAuthentication() {
        properties.setEnabled(false);
    }

    @Test
    void loginReturnsNacosCompatibleTokenResponse() throws Exception {
        loginMockMvc.perform(post("/nacos/v1/auth/users/login")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("username", "bridge-user")
                        .param("password", "bridge-password"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenTtl").value(properties.getTokenTtlSeconds()))
                .andExpect(jsonPath("$.username").value("bridge-user"))
                .andExpect(jsonPath("$.globalAdmin").value(true));
    }

    @Test
    void loginRejectsAConfiguredCredentialMismatch() throws Exception {
        loginMockMvc.perform(post("/nacos/v1/auth/login")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("username", "bridge-user")
                        .param("password", "wrong-password"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("username or password is invalid"));
    }

    @Test
    void protectedRequestRequiresAValidToken() throws Exception {
        mockMvc.perform(get("/nacos/v1/cs/configs")
                        .param("dataId", "application.yaml"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.message").value("invalid or missing accessToken"));

        String token = authService.login("bridge-user", "bridge-password").orElseThrow();
        mockMvc.perform(get("/nacos/v1/cs/configs")
                        .param("dataId", "application.yaml")
                        .param("accessToken", token))
                .andExpect(status().isOk());
    }
}
