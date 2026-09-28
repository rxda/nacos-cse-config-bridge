package com.rxda.nacoscseconfigbridge.config;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.http.HttpStatus;
import org.apache.http.client.config.RequestConfig;
import org.apache.servicecomb.config.kie.client.KieClient;
import org.apache.servicecomb.config.kie.client.model.KVDoc;
import org.apache.servicecomb.config.kie.client.model.KVResponse;
import org.apache.servicecomb.config.kie.client.model.KieAddressManager;
import org.apache.servicecomb.config.kie.client.model.KieConfiguration;
import org.apache.servicecomb.foundation.auth.AuthHeaderProvider;
import org.apache.servicecomb.http.client.auth.RequestAuthHeaderProvider;
import org.apache.servicecomb.http.client.common.HttpConfiguration;
import org.apache.servicecomb.http.client.common.HttpRequest;
import org.apache.servicecomb.http.client.common.HttpResponse;
import org.apache.servicecomb.http.client.common.HttpTransport;
import org.apache.servicecomb.http.client.common.HttpTransportFactory;
import org.apache.servicecomb.http.client.common.HttpUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.google.common.eventbus.EventBus;

/**
 * Creates KIE clients and exposes the raw HTTP query needed to preserve Nacos documents.
 */
@Component
public class KieClientFactory {

    private final KieProperties properties;
    private final KieAddressManager addressManager;
    private final HttpTransport httpTransport;
    private final List<AuthHeaderProvider> authHeaderProviders;

    /**
     * Builds the shared KIE transport and address manager.
     *
     * @param properties KIE endpoint and timeout settings
     * @param authHeaderProviders providers contributing authentication headers
     */
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

    /**
     * Creates a KIE client configured for either an immediate or long-polling request.
     *
     * @param longPolling whether the client should wait for a KIE revision change
     * @return configured KIE client
     */
    public KieClient create(boolean longPolling) {
        KieConfiguration configuration = new KieConfiguration()
                .setProject(properties.getProject())
                .setEnableLongPolling(longPolling)
                .setPollingWaitInSeconds(properties.getPollingWaitSeconds());
        return new KieClient(addressManager, httpTransport, configuration);
    }

    /**
     * Queries KIE without converting typed values into Java maps.
     *
     * @param labelsQuery encoded KIE label predicates
     * @param revision previously observed KIE revision
     * @param longPolling whether the request should wait for a change
     * @return raw KIE response
     */
    public RawQuery queryRaw(String labelsQuery, String revision, boolean longPolling) {
        String effectiveRevision = revision == null || revision.isBlank() ? "-1" : revision;
        StringBuilder url = new StringBuilder(address())
                .append("/v1/").append(encodePathSegment(project())).append("/kie/kv?")
                .append(labelsQuery)
                .append("&revision=").append(encodeQueryValue(effectiveRevision))
                .append("&match=exact");
        if (longPolling) {
            url.append("&wait=").append(Math.max(0, properties.getPollingWaitSeconds())).append("s");
        }

        try {
            HttpResponse response = httpTransport.doRequest(new HttpRequest(url.toString(), null, null, HttpRequest.GET));
            if (response.getStatusCode() == HttpStatus.SC_NOT_MODIFIED
                    || response.getStatusCode() == HttpStatus.SC_TOO_MANY_REQUESTS) {
                // KIE does not repeat the revision header for a 304. Keep the
                // caller's revision so a watch does not fall back to a full
                // query after every quiet polling interval.
                return new RawQuery(false, effectiveRevision, List.of());
            }
            if (response.getStatusCode() != HttpStatus.SC_OK) {
                throw new IllegalStateException("KIE query failed: HTTP " + response.getStatusCode()
                        + (response.getMessage() == null ? "" : " " + response.getMessage()));
            }
            KVResponse body = HttpUtils.deserialize(response.getContent(), KVResponse.class);
            String nextRevision = response.getHeader("X-Kie-Revision");
            if (!StringUtils.hasText(nextRevision)) {
                nextRevision = effectiveRevision;
            }
            List<KVDoc> documents = body == null || body.getData() == null ? List.of() : List.copyOf(body.getData());
            return new RawQuery(true, nextRevision, documents);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to query KIE", e);
        }
    }

    /** Returns the currently selected KIE server address. */
    public String address() {
        return addressManager.address();
    }

    /**
     * 返回配置的源 Nacos 实例标识。
     *
     * <p>该值会写入 KIE 的 {@code nacos-id} 自定义标签。</p>
     *
     * @return 源 Nacos 实例标识
     */
    public String app() {
        return properties.getApp();
    }

    /** Returns the configured CSE project. */
    public String project() {
        return properties.getProject();
    }

    /**
     * Collects authentication headers for direct KIE HTTP requests.
     *
     * @return immutable authentication header map
     */
    public Map<String, String> authHeaders() {
        Map<String, String> headers = new HashMap<>();
        authHeaderProviders.forEach(provider -> headers.putAll(provider.authHeaders()));
        return Collections.unmodifiableMap(headers);
    }
    /**
     * Encodes a value for use as a path segment.
     *
     * @param value value to encode
     * @return encoded path segment
     */
    private static String encodePathSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
    /**
     * Encodes a value for use as a query parameter.
     *
     * @param value value to encode
     * @return encoded query value
     */
    private static String encodeQueryValue(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** Result of a raw KIE query, including the revision used by long polling. */
    public record RawQuery(boolean changed, String revision, List<KVDoc> documents) {
    }
}
