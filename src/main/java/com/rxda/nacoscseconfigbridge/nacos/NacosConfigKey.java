package com.rxda.nacoscseconfigbridge.nacos;

public record NacosConfigKey(String dataId, String group, String tenant) {

    public String effectiveGroup() {
        return group == null || group.isBlank() ? "DEFAULT_GROUP" : group;
    }

    public String effectiveTenant() {
        return tenant == null || tenant.isBlank() ? "public" : tenant;
    }
}
