package com.rxda.nacoscseconfigbridge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration for the Nacos 2.x Config gRPC compatibility endpoint. */
@ConfigurationProperties(prefix = "srv-nacos-cse-config-bridge.grpc")
public class NacosGrpcProperties {

    /** 0 means HTTP server port + 1000, which is Nacos' client-side convention. */
    private int port;

    private boolean enabled = true;

    private int maxInboundMessageSize = 16 * 1024 * 1024;

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxInboundMessageSize() {
        return maxInboundMessageSize;
    }

    public void setMaxInboundMessageSize(int maxInboundMessageSize) {
        this.maxInboundMessageSize = maxInboundMessageSize;
    }
}
