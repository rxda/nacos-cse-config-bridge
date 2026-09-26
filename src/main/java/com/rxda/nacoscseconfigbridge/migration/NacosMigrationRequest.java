package com.rxda.nacoscseconfigbridge.migration;

import java.util.List;

/**
 * Request body for a Nacos-to-KIE migration run.
 *
 * @param sourceServerAddr source Nacos HTTP address
 * @param sourceNamespace namespace to scan when explicit configs are absent
 * @param sourceAccessToken optional source Nacos access token
 * @param overwrite whether existing exact KIE documents may be replaced
 * @param allNamespaces whether every source namespace should be scanned
 * @param configs explicit configurations, or {@code null}/empty for discovery
 */
public record NacosMigrationRequest(
        String sourceServerAddr,
        String sourceNamespace,
        String sourceAccessToken,
        boolean overwrite,
        boolean allNamespaces,
        List<ConfigItem> configs) {

    /**
     * One source Nacos configuration selected for migration.
     *
     * @param dataId source dataId
     * @param group source group
     * @param tenant source namespace
     * @param type Nacos stored type, when available
     */
    public record ConfigItem(String dataId, String group, String tenant, String type) {
        /**
         * Keeps the original request shape; type is optional for explicit selections.
         *
         * @param dataId source dataId
         * @param group source group
         * @param tenant source namespace
         */
        public ConfigItem(String dataId, String group, String tenant) {
            this(dataId, group, tenant, null);
        }
    }
}
