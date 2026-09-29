package com.rxda.nacoscseconfigbridge.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Nacos 2.x Config gRPC 兼容服务的设置。
 */
@ConfigurationProperties(prefix = "srv-nacos-cse-config-bridge.grpc")
@Data
public class NacosGrpcProperties {

    /** 端口覆盖值；为 0 时根据内嵌 HTTP 端口推导。 */
    private int port;
    private boolean enabled = true;
    private int maxInboundMessageSize = 16 * 1024 * 1024;
}
