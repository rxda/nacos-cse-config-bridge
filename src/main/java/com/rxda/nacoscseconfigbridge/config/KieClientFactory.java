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
 * 创建 KIE 客户端，并提供保留 Nacos 文档所需的原始 HTTP 查询。
 */
@Component
public class KieClientFactory {

    private final KieProperties properties;
    private final KieAddressManager addressManager;
    private final HttpTransport httpTransport;
    private final List<AuthHeaderProvider> authHeaderProviders;

    /**
     * 构建共享的 KIE 传输层和地址管理器。
     *
     * @param properties KIE 端点和超时设置
     * @param authHeaderProviders 提供认证头的提供者
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
     * 创建配置为即时请求或长轮询请求的 KIE 客户端。
     *
     * @param longPolling 客户端是否应等待 KIE 版本变更
     * @return 配置好的 KIE 客户端
     */
    public KieClient create(boolean longPolling) {
        KieConfiguration configuration = new KieConfiguration()
                .setProject(properties.getProject())
                .setEnableLongPolling(longPolling)
                .setPollingWaitInSeconds(properties.getPollingWaitSeconds());
        return new KieClient(addressManager, httpTransport, configuration);
    }

    /**
     * 查询 KIE，不将类型化值转换为 Java Map。
     *
     * @param labelsQuery 编码后的 KIE 标签谓词
     * @param revision 之前观察到的 KIE 版本
     * @param longPolling 请求是否应等待变更
     * @return 原始 KIE 响应
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
                // KIE 在 304 响应中不会重复版本头。保留调用方的版本，
                // 避免监听在每次静默轮询间隔后回退到全量查询。
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

    /** 返回当前选中的 KIE 服务地址。 */
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

    /** 返回配置的 CSE 项目。 */
    public String project() {
        return properties.getProject();
    }

    /**
     * 收集直接 KIE HTTP 请求所需的认证头。
     *
     * @return 不可变的认证头映射
     */
    public Map<String, String> authHeaders() {
        Map<String, String> headers = new HashMap<>();
        authHeaderProviders.forEach(provider -> headers.putAll(provider.authHeaders()));
        return Collections.unmodifiableMap(headers);
    }
    /**
     * 将值编码为路径片段使用。
     *
     * @param value 待编码的值
     * @return 编码后的路径片段
     */
    private static String encodePathSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
    /**
     * 将值编码为查询参数使用。
     *
     * @param value 待编码的值
     * @return 编码后的查询参数值
     */
    private static String encodeQueryValue(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** 原始 KIE 查询的结果，包含长轮询使用的版本。 */
    public record RawQuery(boolean changed, String revision, List<KVDoc> documents) {
    }
}
