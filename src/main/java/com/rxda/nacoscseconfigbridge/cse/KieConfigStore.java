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
 * 从 CSE KIE 读取原始的一对一 Nacos 配置文档。
 *
 * <p>该存储刻意保留原始文本，不调用 KIE 格式转换，也不做层级回退解析。</p>
 */

@Service
public class KieConfigStore {

    private final KieClientFactory clientFactory;

    /**
     * 创建仅用于测试、无 KIE 客户端的存储。
     */
    public KieConfigStore() {
        this.clientFactory = null;
    }

    /**
     * 创建由配置的 KIE 客户端工厂支撑的生产存储。
     *
     * @param clientFactory KIE 传输层与身份工厂
     */
    @Autowired
    public KieConfigStore(KieClientFactory clientFactory) {
        this.clientFactory = clientFactory;
    }

    /**
     * 从 KIE 精确读取一个 Nacos 配置标识。
     *
     * @param key Nacos dataId、group 和 tenant 标识
     * @param revision 用于条件轮询的 KIE 版本
     * @param longPolling KIE 是否应挂起请求直到有变更
     * @return 原始内容和版本元数据
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
     * 计算 Nacos 监听协议使用的 MD5。
     *
     * @param content 配置文本
     * @return 小写十六进制 MD5 摘要
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
     * 为 Nacos 配置标识构建精确的 KIE 标签查询。
     *
     * @param key Nacos 配置标识
     * @return 编码后的 KIE 标签谓词
     */
    private String labels(NacosConfigKey key) {
        return identityLabels(key).entrySet().stream()
                .map(entry -> "label=" + encode(entry.getKey() + ":" + entry.getValue()))
                .reduce((left, right) -> left + "&" + right)
                .orElseThrow();
    }
    /**
     * 返回用于隔离单个 Nacos 配置文档的标签。
     *
     * @param key Nacos 配置标识
     * @return 精确的 KIE 标签集合
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
     * 对一个 KIE 标签表达式做 URL 编码。
     *
     * @param value 待编码的值
     * @return 编码后的值
     */
    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
    /**
     * 判断 KIE 文档是否可被读取。
     *
     * @param document KIE 文档
     * @return 文档已启用或无状态时返回 {@code true}
     */
    private boolean isEnabled(KVDoc document) {
        return document.getStatus() == null || "enabled".equalsIgnoreCase(document.getStatus());
    }

    /**
     * 精确 KIE 读取的结果，包含轮询和原始格式元数据。
     */
    public record ReadResult(boolean changed, String revision, Optional<String> content, String valueType) {
        /**
         * 创建不带值类型元数据的读取结果。
         *
         * @param changed KIE 查询是否观察到版本变更
         * @param revision 查询返回的 KIE 版本
         * @param content 原始配置内容（如果存在）
         */
        public ReadResult(boolean changed, String revision, Optional<String> content) {
            this(changed, revision, content, null);
        }
    }
}
