package com.rxda.nacoscseconfigbridge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Nacos 2.x Config gRPC 兼容服务的设置。
 */
@ConfigurationProperties(prefix = "srv-nacos-cse-config-bridge.grpc")
public class NacosGrpcProperties {

    /** 端口覆盖值；为 0 时根据内嵌 HTTP 端口推导。 */
    private int port;
    private boolean enabled = true;
    private int maxInboundMessageSize = 16 * 1024 * 1024;

    /**
     * 返回 gRPC 监听端口覆盖值。
     *
     * @return 配置的端口，0 表示自动推导
     */
    public int getPort() {
        return port;
    }

    /**
     * 设置 gRPC 监听端口覆盖值。
     *
     * @param port 配置的端口，0 表示自动推导
     */
    public void setPort(int port) {
        this.port = port;
    }

    /**
     * 返回兼容服务是否启用。
     *
     * @return 启用 gRPC 时返回 {@code true}
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 启用或禁用兼容服务。
     *
     * @param enabled 是否启用 gRPC
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * 返回 gRPC 入站消息的最大长度。
     *
     * @return 最大消息长度（字节）
     */
    public int getMaxInboundMessageSize() {
        return maxInboundMessageSize;
    }

    /**
     * 设置 gRPC 入站消息的最大长度。
     *
     * @param maxInboundMessageSize 最大消息长度（字节）
     */
    public void setMaxInboundMessageSize(int maxInboundMessageSize) {
        this.maxInboundMessageSize = maxInboundMessageSize;
    }
}
