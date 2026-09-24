package com.rxda.nacoscseconfigbridge.config;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Collections;
import java.util.stream.Collectors;

import org.apache.http.client.config.RequestConfig;
import org.apache.servicecomb.config.kie.client.KieClient;
import org.apache.servicecomb.config.kie.client.model.KieAddressManager;
import org.apache.servicecomb.config.kie.client.model.KieConfiguration;
import org.apache.servicecomb.foundation.auth.AuthHeaderProvider;
import org.apache.servicecomb.http.client.auth.RequestAuthHeaderProvider;
import org.apache.servicecomb.http.client.common.HttpConfiguration;
import org.apache.servicecomb.http.client.common.HttpTransport;
import org.apache.servicecomb.http.client.common.HttpTransportFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.google.common.eventbus.EventBus;
@Component
public class KieClientFactory {

    private final KieProperties properties;
    private final KieAddressManager addressManager;
    private final HttpTransport httpTransport;
    private final List<AuthHeaderProvider> authHeaderProviders;

    public KieClientFactory(KieProperties properties, List<AuthHeaderProvider> authHeaderProviders) {
        this.properties = properties;
        this.authHeaderProviders = List.copyOf(authHeaderProviders);
        if (!StringUtils.hasText(properties.getServerAddr())) {
            throw new IllegalStateException("srv-nacos-cse-config-bridge.kie.server-addr must be configured");
        }
        if (!StringUtils.hasText(properties.getProject())) {
            throw new IllegalStateException("srv-nacos-cse-config-bridge.kie.project must be configured");
        }

        List<String> addresses = Arrays.stream(properties.getServerAddr().split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .collect(Collectors.toList());
        EventBus eventBus = new EventBus("srv-nacos-cse-config-bridge");
        this.addressManager = new KieAddressManager(addresses, eventBus);

        RequestAuthHeaderProvider authProvider = signRequest -> {
            Map<String, String> headers = new java.util.HashMap<>();
            this.authHeaderProviders.forEach(provider -> headers.putAll(provider.authHeaders()));
            return headers;
        };

        HttpConfiguration.SSLProperties sslProperties = new HttpConfiguration.SSLProperties();
        sslProperties.setEnabled(properties.isSslEnabled());
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(5000)
                .setConnectionRequestTimeout(5000)
                .setSocketTimeout(properties.getSocketTimeoutSeconds() * 1000)
                .build();
        this.httpTransport = HttpTransportFactory.createHttpTransport(sslProperties, authProvider, requestConfig);
    }

    public KieClient create(boolean longPolling) {
        KieConfiguration configuration = new KieConfiguration()
                .setProject(properties.getProject())
                .setEnableLongPolling(longPolling)
                .setPollingWaitInSeconds(properties.getPollingWaitSeconds());
        return new KieClient(addressManager, httpTransport, configuration);
    }

    public String address() {
        return addressManager.address();
    }

    public String app() {
        return properties.getApp();
    }

    public String project() {
        return properties.getProject();
    }

    public Map<String, String> authHeaders() {
        Map<String, String> headers = new HashMap<>();
        authHeaderProviders.forEach(provider -> headers.putAll(provider.authHeaders()));
        return Collections.unmodifiableMap(headers);
    }
}
