package com.rxda.nacoscseconfigbridge.migration;

import java.util.List;

/**
 * Nacos 到 KIE 迁移任务的请求体。
 *
 * @param sourceServerAddr 源 Nacos HTTP 地址
 * @param sourceNamespace 未明确指定配置时要扫描的命名空间
 * @param sourceAccessToken 可选的源 Nacos 访问令牌
 * @param overwrite 是否允许替换已存在的精确 KIE 文档
 * @param allNamespaces 是否扫描源端所有命名空间
 * @param configs 明确指定的配置；为 {@code null}/空时走发现流程
 */
public record NacosMigrationRequest(
        String sourceServerAddr,
        String sourceNamespace,
        String sourceAccessToken,
        boolean overwrite,
        boolean allNamespaces,
        List<ConfigItem> configs) {

    /**
     * 选定迁移的一个源 Nacos 配置。
     *
     * @param dataId 源 dataId
     * @param group 源 group
     * @param tenant 源命名空间
     * @param type Nacos 存储类型（如果有）
     */
    public record ConfigItem(String dataId, String group, String tenant, String type) {
        /**
         * 保留原始请求形态；明确指定时 type 可选。
         *
         * @param dataId 源 dataId
         * @param group 源 group
         * @param tenant 源命名空间
         */
        public ConfigItem(String dataId, String group, String tenant) {
            this(dataId, group, tenant, null);
        }
    }
}
