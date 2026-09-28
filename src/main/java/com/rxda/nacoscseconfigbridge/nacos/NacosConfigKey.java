package com.rxda.nacoscseconfigbridge.nacos;

/**
 * 网桥各处使用的不可变 Nacos 配置标识。
 *
 * @param dataId Nacos 配置 dataId
 * @param group Nacos group
 * @param tenant Nacos 命名空间或租户
 */
public record NacosConfigKey(String dataId, String group, String tenant) {

    /**
     * 请求未指定 group 时返回 Nacos 默认 group。
     *
     * @return 有效 group
     */
    public String effectiveGroup() {
        return group == null || group.isBlank() ? "DEFAULT_GROUP" : group;
    }

    /**
     * 请求未指定 tenant 时返回公共租户。
     *
     * @return 有效 tenant
     */
    public String effectiveTenant() {
        return tenant == null || tenant.isBlank() ? "public" : tenant;
    }
}
