package com.rxda.nacoscseconfigbridge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings for the Nacos 2.x Config gRPC compatibility server.
 */
@ConfigurationProperties(prefix = "srv-nacos-cse-config-bridge.grpc")
public class NacosGrpcProperties {

    /** Port override; zero derives the port from the embedded HTTP port. */
    private int port;
    private boolean enabled = true;
    private int maxInboundMessageSize = 16 * 1024 * 1024;

    /**
     * Returns the gRPC listen port override.
     *
     * @return configured port, or zero for automatic derivation
     */
    public int getPort() {
        return port;
    }

    /**
     * Sets the gRPC listen port override.
     *
     * @param port configured port, or zero for automatic derivation
     */
    public void setPort(int port) {
        this.port = port;
    }

    /**
     * Returns whether the compatibility server is enabled.
     *
     * @return {@code true} when gRPC is enabled
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Enables or disables the compatibility server.
     *
     * @param enabled whether gRPC is enabled
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Returns the maximum inbound gRPC message size.
     *
     * @return maximum message size in bytes
     */
    public int getMaxInboundMessageSize() {
        return maxInboundMessageSize;
    }

    /**
     * Sets the maximum inbound gRPC message size.
     *
     * @param maxInboundMessageSize maximum message size in bytes
     */
    public void setMaxInboundMessageSize(int maxInboundMessageSize) {
        this.maxInboundMessageSize = maxInboundMessageSize;
    }
}
