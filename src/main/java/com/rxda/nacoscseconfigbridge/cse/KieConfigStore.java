package com.rxda.nacoscseconfigbridge.cse;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.apache.servicecomb.config.kie.client.model.KVDoc;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.rxda.nacoscseconfigbridge.config.KieClientFactory;
import com.rxda.nacoscseconfigbridge.nacos.NacosConfigKey;

/**
 * Reads raw, one-to-one Nacos configuration documents from CSE KIE.
 *
 * <p>The store deliberately preserves the original text and does not invoke KIE
 * format conversion or hierarchical fallback resolution.</p>
 */

@Service
public class KieConfigStore {

    private final KieClientFactory clientFactory;

    /**
     * Creates a test-only store without a KIE client.
     */
    public KieConfigStore() {
        this.clientFactory = null;
    }

    /**
     * Creates a production store backed by the configured KIE client factory.
     *
     * @param clientFactory KIE transport and identity factory
     */
    @Autowired
    public KieConfigStore(KieClientFactory clientFactory) {
        this.clientFactory = clientFactory;
    }

    /**
     * Reads one exact Nacos configuration identity from KIE.
     *
     * @param key Nacos dataId, group, and tenant identity
     * @param revision KIE revision used for conditional polling
     * @param longPolling whether KIE should hold the request until a change
     * @return raw content and revision metadata
     */
    public ReadResult read(NacosConfigKey key, String revision, boolean longPolling) {
        KieClientFactory.RawQuery response = clientFactory.queryRaw(
                labels(key), revision, longPolling);
        Map<String, String> expectedLabels = identityLabels(key);
        Optional<KVDoc> document = response.documents().stream()
                .filter(this::isEnabled)
                // 不能只依赖 KIE 的 match=exact 参数。原始读取接口必须严格校验，
                // 否则层级式 KIE 查询可能返回相同 key 的 CSE 作用域回退文档。
                .filter(candidate -> expectedLabels.equals(candidate.getLabels()))
                .filter(candidate -> key.dataId().equals(candidate.getKey()))
                // KIE 可能短暂返回同一配置项的多个版本；
                // 这里采用与 Java Chassis 客户端相同的最后写入者胜出规则。
                .max(Comparator.comparingLong(KVDoc::getUpdateTime));
        return new ReadResult(
                response.changed(),
                response.revision(),
                document.map(KVDoc::getValue),
                document.map(KVDoc::getValueType).orElse(null));
    }

    /**
     * Calculates the MD5 used by the Nacos listener protocol.
     *
     * @param content configuration text
     * @return lowercase hexadecimal MD5 digest
     */
    public String md5(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5")
                    .digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(32);
            for (byte item : digest) {
                result.append(String.format("%02x", item));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 is not available", e);
        }
    }
    /**
     * Builds the exact KIE label query for a Nacos configuration identity.
     *
     * @param key Nacos configuration identity
     * @return encoded KIE label predicates
     */
    private String labels(NacosConfigKey key) {
        return identityLabels(key).entrySet().stream()
                .map(entry -> "label=" + encode(entry.getKey() + ":" + entry.getValue()))
                .reduce((left, right) -> left + "&" + right)
                .orElseThrow();
    }
    /**
     * Returns the labels used to isolate one Nacos configuration document.
     *
     * @param key Nacos configuration identity
     * @return exact KIE label set
     */
    private Map<String, String> identityLabels(NacosConfigKey key) {
        Map<String, String> labels = new LinkedHashMap<>();
        // CSE app -> Nacos namespace/tenant。
        labels.put("app", key.effectiveTenant());
        // CSE environment -> Nacos group。
        labels.put("environment", key.effectiveGroup());
        // CSE service -> Nacos dataId。
        labels.put("service", key.dataId());
        // 自定义标签用于隔离源 Nacos 实例。
        labels.put("nacos-id", clientFactory.app());
        return labels;
    }
    /**
     * URL-encodes one KIE label expression.
     *
     * @param value value to encode
     * @return encoded value
     */
    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
    /**
     * Determines whether a KIE document is eligible for reads.
     *
     * @param document KIE document
     * @return {@code true} when the document is enabled or has no status
     */
    private boolean isEnabled(KVDoc document) {
        return document.getStatus() == null || "enabled".equalsIgnoreCase(document.getStatus());
    }

    /**
     * Result of an exact KIE read, including polling and original format metadata.
     */
    public record ReadResult(boolean changed, String revision, Optional<String> content, String valueType) {
        /**
         * Creates a read result without value-type metadata.
         *
         * @param changed whether the KIE query observed a revision change
         * @param revision KIE revision returned by the query
         * @param content raw configuration content, when present
         */
        public ReadResult(boolean changed, String revision, Optional<String> content) {
            this(changed, revision, content, null);
        }
    }
}
