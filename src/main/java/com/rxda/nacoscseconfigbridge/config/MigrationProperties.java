package com.rxda.nacoscseconfigbridge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Nacos 到 KIE 迁移端点的功能开关。
 */
@ConfigurationProperties(prefix = "srv-nacos-cse-config-bridge.migration")
public class MigrationProperties {

    /** 迁移默认关闭，因为该端点会写入 CSE。 */
    private boolean enabled;

    /**
     * 返回是否接受迁移请求。
     *
     * @return 启用迁移时返回 {@code true}
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 启用或禁用迁移端点。
     *
     * @param enabled 是否启用迁移
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
