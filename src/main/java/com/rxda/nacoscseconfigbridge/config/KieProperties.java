package com.rxda.nacoscseconfigbridge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Connection and polling settings for the CSE KIE configuration service.
 */
@ConfigurationProperties(prefix = "srv-nacos-cse-config-bridge.kie")
public class KieProperties {

    private String serverAddr;
    private String project;
    private String app = "nacos-config-bridge";
    private int pollingWaitSeconds = 29;
    private int socketTimeoutSeconds = 40;
    private boolean sslEnabled;

    /**
     * Returns the comma-separated KIE server addresses.
     *
     * @return KIE server addresses
     */
    public String getServerAddr() {
        return serverAddr;
    }

    /**
     * Sets the comma-separated KIE server addresses.
     *
     * @param serverAddr KIE server addresses
     */
    public void setServerAddr(String serverAddr) {
        this.serverAddr = serverAddr;
    }

    /**
     * Returns the KIE project used for bridge data.
     *
     * @return KIE project name
     */
    public String getProject() {
        return project;
    }

    /**
     * Sets the KIE project used for bridge data.
     *
     * @param project KIE project name
     */
    public void setProject(String project) {
        this.project = project;
    }

    /**
     * Returns the application label used to isolate bridge data in KIE.
     *
     * @return KIE application label
     */
    public String getApp() {
        return app;
    }

    /**
     * Sets the application label used to isolate bridge data in KIE.
     *
     * @param app KIE application label
     */
    public void setApp(String app) {
        this.app = app;
    }

    /**
     * Returns the maximum KIE long-poll wait time.
     *
     * @return wait time in seconds
     */
    public int getPollingWaitSeconds() {
        return pollingWaitSeconds;
    }

    /**
     * Sets the maximum KIE long-poll wait time.
     *
     * @param pollingWaitSeconds wait time in seconds
     */
    public void setPollingWaitSeconds(int pollingWaitSeconds) {
        this.pollingWaitSeconds = pollingWaitSeconds;
    }

    /**
     * Returns the KIE socket timeout.
     *
     * @return socket timeout in seconds
     */
    public int getSocketTimeoutSeconds() {
        return socketTimeoutSeconds;
    }

    /**
     * Sets the KIE socket timeout.
     *
     * @param socketTimeoutSeconds socket timeout in seconds
     */
    public void setSocketTimeoutSeconds(int socketTimeoutSeconds) {
        this.socketTimeoutSeconds = socketTimeoutSeconds;
    }

    /**
     * Returns whether TLS is enabled for KIE connections.
     *
     * @return {@code true} when TLS is enabled
     */
    public boolean isSslEnabled() {
        return sslEnabled;
    }

    /**
     * Sets whether TLS is enabled for KIE connections.
     *
     * @param sslEnabled whether TLS is enabled
     */
    public void setSslEnabled(boolean sslEnabled) {
        this.sslEnabled = sslEnabled;
    }
}
