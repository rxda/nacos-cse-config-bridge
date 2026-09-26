package com.rxda.nacoscseconfigbridge.nacos;

/**
 * Immutable Nacos configuration identity used throughout the bridge.
 *
 * @param dataId Nacos configuration dataId
 * @param group Nacos group
 * @param tenant Nacos namespace or tenant
 */
public record NacosConfigKey(String dataId, String group, String tenant) {

    /**
     * Returns the Nacos default group when the request omitted a group.
     *
     * @return effective group
     */
    public String effectiveGroup() {
        return group == null || group.isBlank() ? "DEFAULT_GROUP" : group;
    }

    /**
     * Returns the public tenant when the request omitted a tenant.
     *
     * @return effective tenant
     */
    public String effectiveTenant() {
        return tenant == null || tenant.isBlank() ? "public" : tenant;
    }
}
