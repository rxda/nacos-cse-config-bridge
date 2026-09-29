package com.rxda.nacoscseconfigbridge.migration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import com.rxda.nacoscseconfigbridge.config.KieClientFactory;
import com.rxda.nacoscseconfigbridge.nacos.NacosConfigFormat;
import com.rxda.nacoscseconfigbridge.nacos.NacosConfigKey;

/**
 * 一次性的 Nacos HTTP 到 KIE 导入器。绝不经过 Nacos API 写数据。
 *
 * <p>未指定 {@code configs} 时，服务会发现 {@code sourceNamespace} 下的所有配置。
 * 开启 {@code allNamespaces} 后，会先发现所有 Nacos 命名空间，
 * 再逐个发现每个命名空间下的全部配置。</p>
 */
@Service
public class NacosMigrationService {

    private static final int PAGE_SIZE = 100;

    private final KieClientFactory kieClientFactory;
    private final RestClient restClient;

    /**
     * 使用 Spring 的 HTTP 客户端创建迁移服务。
     *
     * @param kieClientFactory 目标 KIE 连接工厂
     */
    @Autowired
    public NacosMigrationService(KieClientFactory kieClientFactory) {
        this(kieClientFactory, RestClient.builder().build());
    }

    NacosMigrationService(KieClientFactory kieClientFactory, RestClient restClient) {
        this.kieClientFactory = Objects.requireNonNull(kieClientFactory, "kieClientFactory");
        this.restClient = Objects.requireNonNull(restClient, "restClient");
    }

    /**
     * 将选定的 Nacos 配置导入为精确的 KIE 标识。
     *
     * @param request 源、命名空间和覆盖选项
     * @return 逐条迁移汇总
     */
    public NacosMigrationResponse migrate(NacosMigrationRequest request) {
        validate(request);
        List<NacosMigrationResponse.ItemResult> results = new ArrayList<>();
        for (NacosMigrationRequest.ConfigItem item : resolveConfigs(request)) {
            results.add(migrateOne(request, item));
        }
        return summarize(results);
    }
    /** 解析明确指定的迁移条目，或按请求的源范围做发现。 */

    private List<NacosMigrationRequest.ConfigItem> resolveConfigs(NacosMigrationRequest request) {
        if (request.allNamespaces()) {
            if (request.configs() != null && !request.configs().isEmpty()) {
                throw new IllegalArgumentException("configs must be empty when allNamespaces is true");
            }
            List<NacosMigrationRequest.ConfigItem> configs = new ArrayList<>();
            for (String namespace : listNamespaces(request)) {
                configs.addAll(discoverConfigs(request, namespace));
            }
            return configs;
        }
        if (request.configs() != null && !request.configs().isEmpty()) {
            return List.copyOf(request.configs());
        }
        return discoverConfigs(request, effectiveTenant(null, request.sourceNamespace()));
    }
    /** 发现一个 Nacos 命名空间下的全部配置条目。 */

    private List<String> listNamespaces(NacosMigrationRequest request) {
        var builder = UriComponentsBuilder.fromUriString(normalizeAddress(request.sourceServerAddr()))
                .path("/nacos/v1/console/namespaces");
        applySourceAccessToken(builder, request);
        Map<?, ?> response = getJson(builder, Map.class);
        if (response == null || !(response.get("data") instanceof List<?> data)) {
            throw new IllegalStateException("source Nacos namespace response does not contain data");
        }
        Set<String> namespaces = new LinkedHashSet<>();
        for (Object value : data) {
            if (value instanceof Map<?, ?> namespace) {
                Object id = namespace.get("namespace");
                namespaces.add(effectiveTenant(id == null ? null : id.toString(), "public"));
            }
        }
        if (namespaces.isEmpty()) {
            throw new IllegalStateException("source Nacos returned no namespaces");
        }
        return List.copyOf(namespaces);
    }
    /** 每次从源 Nacos 配置列表 API 读取一页。 */

    private List<NacosMigrationRequest.ConfigItem> discoverConfigs(NacosMigrationRequest request, String namespace) {
        List<NacosMigrationRequest.ConfigItem> configs = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        int pageNo = 1;
        int processed = 0;
        while (true) {
            var builder = UriComponentsBuilder.fromUriString(normalizeAddress(request.sourceServerAddr()))
                    .path("/nacos/v1/cs/configs")
                    .queryParam("search", "blur")
                    .queryParam("dataId", "")
                    .queryParam("group", "")
                    .queryParam("tenant", nacosTenant(namespace))
                    .queryParam("pageNo", pageNo)
                    .queryParam("pageSize", PAGE_SIZE);
            applySourceAccessToken(builder, request);
            Map<?, ?> page = getJson(builder, Map.class);
            if (page == null || !(page.get("pageItems") instanceof List<?> items)) {
                throw new IllegalStateException("source Nacos config response does not contain pageItems");
            }
            int pageItemCount = 0;
            for (Object value : items) {
                if (!(value instanceof Map<?, ?> item)) {
                    continue;
                }
                pageItemCount++;
                Object dataIdValue = item.get("dataId");
                String dataId = dataIdValue == null ? null : dataIdValue.toString();
                String group = effectiveGroup(asString(item.get("group")));
                String itemTenant = effectiveTenant(asString(item.get("tenant")), namespace);
                String identity = dataId + "\u0000" + group + "\u0000" + itemTenant;
                if (dataId != null && !dataId.isBlank() && seen.add(identity)) {
                    configs.add(new NacosMigrationRequest.ConfigItem(
                            dataId, group, itemTenant, asString(item.get("type"))));
                    processed++;
                }
            }
            int totalCount = asInt(page.get("totalCount"));
            int pagesAvailable = asInt(page.get("pagesAvailable"));
            if (pageItemCount == 0 || pageItemCount < PAGE_SIZE || (totalCount > 0 && processed >= totalCount)) {
                break;
            }
            if (pagesAvailable > 0 && pageNo >= pagesAvailable) {
                break;
            }
            if (pageItemCount > 0 && seen.size() == processed && configs.isEmpty()) {
                break;
            }
            pageNo++;
        }
        return configs;
    }
    /** 拉取并写入一个源配置，保留其存储类型。 */

    private NacosMigrationResponse.ItemResult migrateOne(
            NacosMigrationRequest request, NacosMigrationRequest.ConfigItem item) {
        String dataId = item == null ? null : item.dataId();
        String group = effectiveGroup(item == null ? null : item.group());
        String tenant = effectiveTenant(item == null ? null : item.tenant(), request.sourceNamespace());
        if (dataId == null || dataId.isBlank()) {
            return result(dataId, group, tenant, "FAILED", "dataId is required");
        }
        try {
            String content = readSource(request, new NacosConfigKey(dataId, group, tenant));
            if (content == null) {
                return result(dataId, group, tenant, "SKIPPED", "source Nacos config does not exist");
            }
            writeKie(new NacosConfigKey(dataId, group, tenant), content, item.type(), request.overwrite());
            return result(dataId, group, tenant, "SUCCESS", "migrated");
        } catch (RestClientResponseException e) {
            return result(dataId, group, tenant, "FAILED",
                    "HTTP " + e.getStatusCode().value() + ": " + e.getResponseBodyAsString());
        } catch (RuntimeException e) {
            return result(dataId, group, tenant, "FAILED", safeMessage(e));
        }
    }
    /** 读取请求的配置或元数据。 */

    private String readSource(NacosMigrationRequest request, NacosConfigKey key) {
        var builder = UriComponentsBuilder.fromUriString(normalizeAddress(request.sourceServerAddr()))
                .path("/nacos/v1/cs/configs")
                .queryParam("dataId", key.dataId())
                .queryParam("group", key.effectiveGroup())
                .queryParam("tenant", nacosTenant(key.effectiveTenant()));
        applySourceAccessToken(builder, request);
        try {
            return restClient.get().uri(builder.build().encode().toUri()).retrieve().body(String.class);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                return null;
            }
            throw e;
        }
    }
    /** 把 JSON 值转为适合 KIE 写 API 的 Map。 */

    private <T> T getJson(UriComponentsBuilder builder, Class<T> type) {
        return restClient.get()
                .uri(builder.build().encode().toUri())
                .retrieve()
                .body(type);
    }
    /** 配置了源 Nacos 访问令牌时，将其加到出站请求上。 */

    private void applySourceAccessToken(UriComponentsBuilder builder, NacosMigrationRequest request) {
        if (request.sourceAccessToken() != null && !request.sourceAccessToken().isBlank()) {
            builder.queryParam("accessToken", request.sourceAccessToken());
        }
    }
    /** 为迁移的 Nacos 配置创建精确的 KIE 文档。 */

    private void writeKie(NacosConfigKey key, String content, String nacosType, boolean overwrite) {
        Map<String, String> labels = labels(key);
        if (overwrite) {
            String existingId = findExistingId(key, labels);
            if (existingId != null) {
                updateKie(existingId, key.dataId(), nacosType, content);
                return;
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("key", key.dataId());
        body.put("value", content);
        body.put("value_type", NacosConfigFormat.fromNacosType(nacosType, key.dataId()));
        body.put("status", "enabled");
        body.put("labels", labels);
        restClient.post()
                .uri(kieCollectionUri())
                .headers(this::applyKieHeaders)
                .body(body)
                .retrieve()
                .toBodilessEntity();
    }

    /** 查找具有相同精确标识标签的已有 KIE 文档。 */
    @SuppressWarnings("unchecked")
    private String findExistingId(NacosConfigKey key, Map<String, String> labels) {
        var builder = UriComponentsBuilder.fromUri(kieCollectionUri())
                .queryParam("key", key.dataId())
                .queryParam("match", "exact")
                .queryParam("limit", "100")
                .queryParam("offset", "0");
        labels.forEach((name, value) -> builder.queryParam("label", name + ":" + value));
        Map<String, Object> response = restClient.get()
                .uri(builder.build().encode().toUri())
                .headers(this::applyKieHeaders)
                .retrieve()
                .body(Map.class);
        if (response == null || !(response.get("data") instanceof List<?> data)) {
            return null;
        }
        for (Object value : data) {
            if (!(value instanceof Map<?, ?> item) || item.get("id") == null) {
                continue;
            }
            // 迁移的更新必须落在与读取一致的一对一标识上。
            // 不能因为服务端对非精确查询返回了层级/回退文档就去更新它。
            if (key.dataId().equals(String.valueOf(item.get("key")))
                    && labelsEqual(item.get("labels"), labels)) {
                return item.get("id").toString();
            }
        }
        return null;
    }
    /** 比较 KIE 标签，不依赖 Map 迭代顺序。 */

    private boolean labelsEqual(Object actual, Map<String, String> expected) {
        if (!(actual instanceof Map<?, ?> actualMap) || actualMap.size() != expected.size()) {
            return false;
        }
        for (Map.Entry<String, String> entry : expected.entrySet()) {
            if (!entry.getValue().equals(String.valueOf(actualMap.get(entry.getKey())))) {
                return false;
            }
        }
        return true;
    }
    /** 更新已有的 KIE 文档，同时保留其标识。 */

    private void updateKie(String id, String dataId, String nacosType, String content) {
        Map<String, Object> body = Map.of(
                "value", content,
                "value_type", NacosConfigFormat.fromNacosType(nacosType, dataId),
                "status", "enabled");
        restClient.put()
                .uri(UriComponentsBuilder.fromUri(kieCollectionUri()).pathSegment(id).build().toUri())
                .headers(this::applyKieHeaders)
                .body(body)
                .retrieve()
                .toBodilessEntity();
    }
    /** 构建 Nacos 配置键对应的精确 KIE 标签。 */

    private Map<String, String> labels(NacosConfigKey key) {
        Map<String, String> labels = new LinkedHashMap<>();
        // CSE app -> Nacos namespace/tenant。
        labels.put("app", key.effectiveTenant());
        // CSE environment -> Nacos group。
        labels.put("environment", key.effectiveGroup());
        // CSE service -> Nacos dataId。
        labels.put("service", key.dataId());
        // 自定义标签用于隔离源 Nacos 实例。
        labels.put("nacos-id", kieClientFactory.app());
        return labels;
    }
    /** 构建配置的项目对应的 KIE 集合端点。 */

    private java.net.URI kieCollectionUri() {
        return UriComponentsBuilder.fromUriString(kieClientFactory.address())
                .path("/v1/")
                .pathSegment(kieClientFactory.project())
                .path("/kie/kv")
                .build()
                .toUri();
    }
    /** 给 KIE 请求加上认证和内容头。 */

    private void applyKieHeaders(HttpHeaders headers) {
        kieClientFactory.authHeaders().forEach(headers::set);
        headers.set(HttpHeaders.CONTENT_TYPE, "application/json");
    }
    /** 校验迁移请求的必填字段。 */

    private void validate(NacosMigrationRequest request) {
        if (request == null || request.sourceServerAddr() == null || request.sourceServerAddr().isBlank()) {
            throw new IllegalArgumentException("sourceServerAddr is required");
        }
    }
    /** 把逐条结果汇总为迁移响应。 */

    private NacosMigrationResponse summarize(List<NacosMigrationResponse.ItemResult> results) {
        int success = (int) results.stream().filter(item -> "SUCCESS".equals(item.status())).count();
        int skipped = (int) results.stream().filter(item -> "SKIPPED".equals(item.status())).count();
        return new NacosMigrationResponse(
                results.size(), success, skipped, results.size() - success - skipped, List.copyOf(results));
    }
    /** 规范化 Nacos 基础地址；缺少协议时补上 HTTP scheme。 */

    private String normalizeAddress(String address) {
        String value = address.trim();
        return value.startsWith("http://") || value.startsWith("https://") ? value : "http://" + value;
    }
    /** 请求未指定 group 时返回 Nacos 默认分组。 */

    private String effectiveGroup(String group) {
        return group == null || group.isBlank() ? "DEFAULT_GROUP" : group;
    }
    /** 请求和条目都未指定租户时返回 Nacos 默认 public 租户。 */

    private String effectiveTenant(String itemTenant, String requestTenant) {
        if (itemTenant != null && !itemTenant.isBlank()) {
            return itemTenant;
        }
        return requestTenant == null || requestTenant.isBlank() ? "public" : requestTenant;
    }
    /** 把 public 租户转换为 Nacos 查询接口使用的空租户值。 */

    private String nacosTenant(String tenant) {
        return "public".equals(tenant) ? "" : tenant;
    }
    /** 把可选的响应字段转换为字符串。 */

    private String asString(Object value) {
        return value == null ? null : value.toString();
    }
    /** 把可选的响应字段转换为整数，转换失败时返回 0。 */

    private int asInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
    /** 创建单条配置的迁移结果。 */

    private NacosMigrationResponse.ItemResult result(
            String dataId, String group, String tenant, String status, String message) {
        return new NacosMigrationResponse.ItemResult(dataId, group, tenant, status, message);
    }
    /** 从异常中提取非空诊断信息，没有时返回异常类名。 */

    private String safeMessage(RuntimeException e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }
}
