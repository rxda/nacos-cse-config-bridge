package com.rxda.nacoscseconfigbridge.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * CSE KIE 配置服务的连接和轮询设置。
 */
@ConfigurationProperties(prefix = "srv-nacos-cse-config-bridge.kie")
@Data
public class KieProperties {

    /**
     * 逗号分隔的 KIE 服务地址。
     */
    private String serverAddr;

    /**
     * 用于桥接数据的 KIE 项目。
     */
    private String project;

    /**
     * 会写入 KIE {@code nacos-id} 自定义标签的源 Nacos 实例标识。
     */
    private String app = "nacos-config-bridge";

    /**
     * KIE 长轮询的最长等待时间，单位为秒。
     */
    private int pollingWaitSeconds = 29;

    /**
     * KIE 套接字超时时间，单位为秒。
     */
    private int socketTimeoutSeconds = 40;

    /**
     * KIE 连接是否启用 TLS。
     */
    private boolean sslEnabled;
}
