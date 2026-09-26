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

    /**
     * Creates a migration service using Spring's HTTP client.
     *
     * @param kieClientFactory destination KIE connection factory
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
     * Imports the selected Nacos configurations into exact KIE identities.
     *
     * @param request source, namespace, and overwrite options
     * @return per-item migration summary
     */
    public NacosMigrationResponse migrate(NacosMigrationRequest request) {
        validate(request);
        List<NacosMigrationResponse.ItemResult> results = new ArrayList<>();
        for (NacosMigrationRequest.ConfigItem item : resolveConfigs(request)) {
            results.add(migrateOne(request, item));
        }
        return summarize(results);
    }
    /** Resolves explicit migration items or discovers the requested source scope. */

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
    /** Discovers every configuration item in one Nacos namespace. */

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
    /** Reads one page at a time from the source Nacos configuration list API. */

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
    /** Fetches and writes one source configuration, preserving its stored type. */

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
    /** Reads the requested configuration or metadata. */

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
    /** Returns a JSON value as a map suitable for the KIE write API. */

    private <T> T getJson(UriComponentsBuilder builder, Class<T> type) {
        return restClient.get()
                .uri(builder.build().encode().toUri())
                .retrieve()
                .body(type);
    }
    /** Adds the source Nacos access token to an outgoing request when configured. */

    private void applySourceAccessToken(UriComponentsBuilder builder, NacosMigrationRequest request) {
        if (request.sourceAccessToken() != null && !request.sourceAccessToken().isBlank()) {
            builder.queryParam("accessToken", request.sourceAccessToken());
        }
    }
    /** Creates one exact KIE document for a migrated Nacos configuration. */

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

    /** Finds an existing KIE document with the same exact identity labels. */
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
            // Keep migration upserts on the same one-to-one identity as reads.
            // Do not update a KIE hierarchy/fallback document merely because
            // the server returned it for a non-exact query.
            if (key.dataId().equals(String.valueOf(item.get("key")))
                    && labelsEqual(item.get("labels"), labels)) {
                return item.get("id").toString();
            }
        }
        return null;
    }
    /** Compares KIE labels without depending on map iteration order. */

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
    /** Updates an existing KIE document while preserving its identity. */

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
    /** Builds the exact KIE labels corresponding to a Nacos configuration key. */

    private Map<String, String> labels(NacosConfigKey key) {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("app", kieClientFactory.app());
        labels.put("environment", key.effectiveTenant());
        labels.put("service", key.effectiveGroup());
        labels.put("nacos-data-id", key.dataId());
        return labels;
    }
    /** Builds the KIE collection endpoint for the configured project. */

    private java.net.URI kieCollectionUri() {
        return UriComponentsBuilder.fromUriString(kieClientFactory.address())
                .path("/v1/")
                .pathSegment(kieClientFactory.project())
                .path("/kie/kv")
                .build()
                .toUri();
    }
    /** Applies authentication and content headers to a KIE request. */

    private void applyKieHeaders(HttpHeaders headers) {
        kieClientFactory.authHeaders().forEach(headers::set);
        headers.set(HttpHeaders.CONTENT_TYPE, "application/json");
    }
    /** Validates the required migration request fields. */

    private void validate(NacosMigrationRequest request) {
        if (request == null || request.sourceServerAddr() == null || request.sourceServerAddr().isBlank()) {
            throw new IllegalArgumentException("sourceServerAddr is required");
        }
    }
    /** Aggregates per-item outcomes into the migration response. */

    private NacosMigrationResponse summarize(List<NacosMigrationResponse.ItemResult> results) {
        int success = (int) results.stream().filter(item -> "SUCCESS".equals(item.status())).count();
        int skipped = (int) results.stream().filter(item -> "SKIPPED".equals(item.status())).count();
        return new NacosMigrationResponse(
                results.size(), success, skipped, results.size() - success - skipped, List.copyOf(results));
    }
    /** Normalizes a Nacos base address by adding an HTTP scheme when absent. */

    private String normalizeAddress(String address) {
        String value = address.trim();
        return value.startsWith("http://") || value.startsWith("https://") ? value : "http://" + value;
    }
    /** Returns the Nacos default group when the request omits the group. */

    private String effectiveGroup(String group) {
        return group == null || group.isBlank() ? "DEFAULT_GROUP" : group;
    }
    /** Returns the Nacos default group when the request omits the group. */

    private String effectiveTenant(String itemTenant, String requestTenant) {
        if (itemTenant != null && !itemTenant.isBlank()) {
            return itemTenant;
        }
        return requestTenant == null || requestTenant.isBlank() ? "public" : requestTenant;
    }
    /** Converts the public tenant to the empty tenant query value used by Nacos. */

    private String nacosTenant(String tenant) {
        return "public".equals(tenant) ? "" : tenant;
    }
    /** Converts an optional response field to a string. */

    private String asString(Object value) {
        return value == null ? null : value.toString();
    }
    /** Converts an optional response field to an integer, defaulting to zero. */

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
    /** Creates a per-item migration result. */

    private NacosMigrationResponse.ItemResult result(
            String dataId, String group, String tenant, String status, String message) {
        return new NacosMigrationResponse.ItemResult(dataId, group, tenant, status, message);
    }
    /** Extracts a non-empty diagnostic message from an exception. */

    private String safeMessage(RuntimeException e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }
}
