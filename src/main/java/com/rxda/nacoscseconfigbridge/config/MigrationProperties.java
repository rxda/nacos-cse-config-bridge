package com.rxda.nacoscseconfigbridge.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Nacos 到 KIE 迁移端点的功能开关。
 */
@ConfigurationProperties(prefix = "srv-nacos-cse-config-bridge.migration")
@Data
public class MigrationProperties {

    /** 迁移默认关闭，因为该端点会写入 CSE。 */
    private boolean enabled;
}
