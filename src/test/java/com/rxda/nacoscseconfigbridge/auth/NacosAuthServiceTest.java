package com.rxda.nacoscseconfigbridge.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.rxda.nacoscseconfigbridge.config.NacosAuthProperties;

class NacosAuthServiceTest {

    private NacosAuthProperties properties;
    private NacosAuthService service;

    @BeforeEach
    void createService() throws Exception {
        properties = new NacosAuthProperties();
        properties.setEnabled(true);
        properties.setUsername("configured-user");
        properties.setPassword("configured-password");
        service = new NacosAuthService(properties);
        service.afterPropertiesSet();
    }

    @Test
    void configuredModeOnlyAcceptsTheConfiguredPair() {
        String token = service.login("configured-user", "configured-password").orElseThrow();

        assertThat(service.isTokenValid(token)).isTrue();
        assertThat(service.login("configured-user", "wrong-password")).isEmpty();
        assertThat(service.login("other-user", "configured-password")).isEmpty();
        assertThat(service.isTokenValid("unknown-token")).isFalse();
    }

    @Test
    void anyCredentialsModeAcceptsEveryNonEmptyPair() throws Exception {
        properties.setAcceptAnyCredentials(true);
        properties.setTokenSecret("shared-stateless-secret");
        service.afterPropertiesSet();

        assertThat(service.login("alice", "first-password")).isPresent();
        assertThat(service.login("bob", "second-password")).isPresent();
        assertThat(service.login("", "password")).isEmpty();
        assertThat(service.login("alice", " ")).isEmpty();
    }

    @Test
    void tokenSignedByOneReplicaIsAcceptedByAnother() {
        NacosAuthProperties replicaProperties = new NacosAuthProperties();
        replicaProperties.setEnabled(true);
        replicaProperties.setUsername("configured-user");
        replicaProperties.setPassword("configured-password");
        NacosAuthService replica = new NacosAuthService(replicaProperties);
        replica.afterPropertiesSet();

        String token = service.login("configured-user", "configured-password")
                .orElseThrow();

        assertThat(replica.isTokenValid(token)).isTrue();
    }

    @Test
    void tokenSignedWithADifferentSecretIsRejected() throws Exception {
        NacosAuthProperties otherReplicaProperties = new NacosAuthProperties();
        otherReplicaProperties.setEnabled(true);
        otherReplicaProperties.setUsername("configured-user");
        otherReplicaProperties.setPassword("configured-password");
        otherReplicaProperties.setTokenSecret("another-shared-secret");
        NacosAuthService otherReplica = new NacosAuthService(otherReplicaProperties);
        otherReplica.afterPropertiesSet();

        String token = service.login("configured-user", "configured-password")
                .orElseThrow();

        assertThat(otherReplica.isTokenValid(token)).isFalse();
    }

    @Test
    void anyCredentialsModeRequiresASharedTokenSecret() {
        properties.setAcceptAnyCredentials(true);

        assertThatThrownBy(service::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NACOS_AUTH_TOKEN_SECRET");
    }

    @Test
    void disabledAuthenticationAllowsAnonymousRequests() throws Exception {
        properties.setEnabled(false);
        NacosAuthService disabledService = new NacosAuthService(properties);
        disabledService.afterPropertiesSet();

        assertThat(disabledService.login("client", "secret")).isPresent();
        assertThat(disabledService.isTokenValid(null)).isTrue();
        assertThat(disabledService.isTokenValid("not-issued")).isTrue();
    }

    @Test
    void strictModeRequiresBothConfiguredCredentials() {
        properties.setUsername("");
        properties.setPassword("");
        NacosAuthService invalidService = new NacosAuthService(properties);

        assertThatThrownBy(invalidService::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("用户名和密码");
    }

    @Test
    void tokenTtlMustBePositive() {
        properties.setTokenTtlSeconds(0);
        NacosAuthService invalidService = new NacosAuthService(properties);

        assertThatThrownBy(invalidService::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("大于 0 秒");
    }
}
