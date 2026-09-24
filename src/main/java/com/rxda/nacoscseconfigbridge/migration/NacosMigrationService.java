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
import com.rxda.nacoscseconfigbridge.nacos.NacosConfigKey;

/**
 * One-shot Nacos HTTP to KIE importer. It never writes through Nacos APIs.
 *
 * <p>When {@code configs} is omitted, the service discovers every config in
 * {@code sourceNamespace}. With {@code allNamespaces}, it first discovers all
 * Nacos namespaces and then discovers every config in each namespace.</p>
 */
@Service
public class NacosMigrationService {

    private static final int PAGE_SIZE = 100;

    private final KieClientFactory kieClientFactory;
    private final RestClient restClient;

    @Autowired
    public NacosMigrationService(KieClientFactory kieClientFactory) {
        this(kieClientFactory, RestClient.builder().build());
    }

    NacosMigrationService(KieClientFactory kieClientFactory, RestClient restClient) {
        this.kieClientFactory = Objects.requireNonNull(kieClientFactory, "kieClientFactory");
        this.restClient = Objects.requireNonNull(restClient, "restClient");
    }

    public NacosMigrationResponse migrate(NacosMigrationRequest request) {
        validate(request);
        List<NacosMigrationResponse.ItemResult> results = new ArrayList<>();
        for (NacosMigrationRequest.ConfigItem item : resolveConfigs(request)) {
            results.add(migrateOne(request, item));
        }
        return summarize(results);
    }

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
                    configs.add(new NacosMigrationRequest.ConfigItem(dataId, group, itemTenant));
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
            writeKie(new NacosConfigKey(dataId, group, tenant), content, request.overwrite());
            return result(dataId, group, tenant, "SUCCESS", "migrated");
        } catch (RestClientResponseException e) {
            return result(dataId, group, tenant, "FAILED",
                    "HTTP " + e.getStatusCode().value() + ": " + e.getResponseBodyAsString());
        } catch (RuntimeException e) {
            return result(dataId, group, tenant, "FAILED", safeMessage(e));
        }
    }

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

    private <T> T getJson(UriComponentsBuilder builder, Class<T> type) {
        return restClient.get()
                .uri(builder.build().encode().toUri())
                .retrieve()
                .body(type);
    }

    private void applySourceAccessToken(UriComponentsBuilder builder, NacosMigrationRequest request) {
        if (request.sourceAccessToken() != null && !request.sourceAccessToken().isBlank()) {
            builder.queryParam("accessToken", request.sourceAccessToken());
        }
    }

    private void writeKie(NacosConfigKey key, String content, boolean overwrite) {
        Map<String, String> labels = labels(key);
        if (overwrite) {
            String existingId = findExistingId(key, labels);
            if (existingId != null) {
                updateKie(existingId, content);
                return;
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("key", key.dataId());
        body.put("value", content);
        body.put("value_type", "text");
        body.put("status", "enabled");
        body.put("labels", labels);
        restClient.post()
                .uri(kieCollectionUri())
                .headers(this::applyKieHeaders)
                .body(body)
                .retrieve()
                .toBodilessEntity();
    }

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
            if (value instanceof Map<?, ?> item && item.get("id") != null) {
                return item.get("id").toString();
            }
        }
        return null;
    }

    private void updateKie(String id, String content) {
        Map<String, Object> body = Map.of("value", content, "value_type", "text", "status", "enabled");
        restClient.put()
                .uri(UriComponentsBuilder.fromUri(kieCollectionUri()).pathSegment(id).build().toUri())
                .headers(this::applyKieHeaders)
                .body(body)
                .retrieve()
                .toBodilessEntity();
    }

    private Map<String, String> labels(NacosConfigKey key) {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("app", kieClientFactory.app());
        labels.put("environment", key.effectiveTenant());
        labels.put("service", key.effectiveGroup());
        labels.put("nacos-data-id", key.dataId());
        return labels;
    }

    private java.net.URI kieCollectionUri() {
        return UriComponentsBuilder.fromUriString(kieClientFactory.address())
                .path("/v1/")
                .pathSegment(kieClientFactory.project())
                .path("/kie/kv")
                .build()
                .toUri();
    }

    private void applyKieHeaders(HttpHeaders headers) {
        kieClientFactory.authHeaders().forEach(headers::set);
        headers.set(HttpHeaders.CONTENT_TYPE, "application/json");
    }

    private void validate(NacosMigrationRequest request) {
        if (request == null || request.sourceServerAddr() == null || request.sourceServerAddr().isBlank()) {
            throw new IllegalArgumentException("sourceServerAddr is required");
        }
    }

    private NacosMigrationResponse summarize(List<NacosMigrationResponse.ItemResult> results) {
        int success = (int) results.stream().filter(item -> "SUCCESS".equals(item.status())).count();
        int skipped = (int) results.stream().filter(item -> "SKIPPED".equals(item.status())).count();
        return new NacosMigrationResponse(
                results.size(), success, skipped, results.size() - success - skipped, List.copyOf(results));
    }

    private String normalizeAddress(String address) {
        String value = address.trim();
        return value.startsWith("http://") || value.startsWith("https://") ? value : "http://" + value;
    }

    private String effectiveGroup(String group) {
        return group == null || group.isBlank() ? "DEFAULT_GROUP" : group;
    }

    private String effectiveTenant(String itemTenant, String requestTenant) {
        if (itemTenant != null && !itemTenant.isBlank()) {
            return itemTenant;
        }
        return requestTenant == null || requestTenant.isBlank() ? "public" : requestTenant;
    }

    private String nacosTenant(String tenant) {
        return "public".equals(tenant) ? "" : tenant;
    }

    private String asString(Object value) {
        return value == null ? null : value.toString();
    }

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

    private NacosMigrationResponse.ItemResult result(
            String dataId, String group, String tenant, String status, String message) {
        return new NacosMigrationResponse.ItemResult(dataId, group, tenant, status, message);
    }

    private String safeMessage(RuntimeException e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }
}
