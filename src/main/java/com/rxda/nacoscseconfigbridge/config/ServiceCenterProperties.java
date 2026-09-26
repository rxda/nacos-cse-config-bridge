package com.rxda.nacoscseconfigbridge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings controlling optional registration of Nacos clients in CSE Service Center.
 */
@ConfigurationProperties(prefix = "srv-nacos-cse-config-bridge.service-center")
public class ServiceCenterProperties {

    /** Registration is attempted only when this flag and a server address are set. */
    private boolean enabled;
    private String serverAddr;
    private String project = "default";
    private String tenantName = "default";
    private String appId = "default";
    private String environment = "";
    private String version = "1.0.0";
    private long heartbeatIntervalSeconds = 15;
    private boolean instanceEnabled = true;
    private String serviceNameLabel = "AppName";
    private String endpointLabel = "";
    private String hostLabel = "serviceHost";
    private String portLabel = "servicePort";
    private String protocolLabel = "serviceProtocol";
    /** Optional fallback business port when a Nacos client does not send servicePort. */
    private int instancePort;
    /** Optional fallback business host; blank means use the gRPC peer address. */
    private String instanceHost;
    private String defaultServiceName;
    private boolean sslEnabled;

    /**
     * Returns whether Service Center registration is enabled.
     *
     * @return {@code true} when registration is enabled
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Enables or disables Service Center registration.
     *
     * @param enabled whether registration is enabled
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Returns the comma-separated Service Center addresses.
     *
     * @return Service Center addresses
     */
    public String getServerAddr() {
        return serverAddr;
    }

    /**
     * Sets the comma-separated Service Center addresses.
     *
     * @param serverAddr Service Center addresses
     */
    public void setServerAddr(String serverAddr) {
        this.serverAddr = serverAddr;
    }

    /**
     * Returns the CSE project.
     *
     * @return CSE project name
     */
    public String getProject() {
        return project;
    }

    /**
     * Sets the CSE project.
     *
     * @param project CSE project name
     */
    public void setProject(String project) {
        this.project = project;
    }

    /**
     * Returns the Service Center tenant.
     *
     * @return tenant name
     */
    public String getTenantName() {
        return tenantName;
    }

    /**
     * Sets the Service Center tenant.
     *
     * @param tenantName tenant name
     */
    public void setTenantName(String tenantName) {
        this.tenantName = tenantName;
    }

    /**
     * Returns the CSE application identifier.
     *
     * @return application identifier
     */
    public String getAppId() {
        return appId;
    }

    /**
     * Sets the CSE application identifier.
     *
     * @param appId application identifier
     */
    public void setAppId(String appId) {
        this.appId = appId;
    }

    /**
     * Returns the fallback CSE environment.
     *
     * @return environment name
     */
    public String getEnvironment() {
        return environment;
    }

    /**
     * Sets the fallback CSE environment.
     *
     * @param environment environment name
     */
    public void setEnvironment(String environment) {
        this.environment = environment;
    }

    /**
     * Returns the microservice version advertised to Service Center.
     *
     * @return service version
     */
    public String getVersion() {
        return version;
    }

    /**
     * Sets the microservice version advertised to Service Center.
     *
     * @param version service version
     */
    public void setVersion(String version) {
        this.version = version;
    }

    /**
     * Returns the instance heartbeat interval.
     *
     * @return heartbeat interval in seconds
     */
    public long getHeartbeatIntervalSeconds() {
        return heartbeatIntervalSeconds;
    }

    /**
     * Sets the instance heartbeat interval.
     *
     * @param heartbeatIntervalSeconds heartbeat interval in seconds
     */
    public void setHeartbeatIntervalSeconds(long heartbeatIntervalSeconds) {
        this.heartbeatIntervalSeconds = heartbeatIntervalSeconds;
    }

    /**
     * Returns whether business instances should be registered.
     *
     * @return {@code true} when instance registration is enabled
     */
    public boolean isInstanceEnabled() {
        return instanceEnabled;
    }

    /**
     * Enables or disables business instance registration.
     *
     * @param instanceEnabled whether instance registration is enabled
     */
    public void setInstanceEnabled(boolean instanceEnabled) {
        this.instanceEnabled = instanceEnabled;
    }

    /**
     * Returns the label containing the Nacos application or service name.
     *
     * @return service-name label
     */
    public String getServiceNameLabel() {
        return serviceNameLabel;
    }

    /**
     * Sets the label containing the Nacos application or service name.
     *
     * @param serviceNameLabel service-name label
     */
    public void setServiceNameLabel(String serviceNameLabel) {
        this.serviceNameLabel = serviceNameLabel;
    }

    /**
     * Returns the label containing a complete endpoint URI.
     *
     * @return endpoint label, or blank when separate host and port labels are used
     */
    public String getEndpointLabel() {
        return endpointLabel;
    }

    /**
     * Sets the label containing a complete endpoint URI.
     *
     * @param endpointLabel endpoint label
     */
    public void setEndpointLabel(String endpointLabel) {
        this.endpointLabel = endpointLabel;
    }

    /**
     * Returns the label containing the business host.
     *
     * @return host label
     */
    public String getHostLabel() {
        return hostLabel;
    }

    /**
     * Sets the label containing the business host.
     *
     * @param hostLabel host label
     */
    public void setHostLabel(String hostLabel) {
        this.hostLabel = hostLabel;
    }

    /**
     * Returns the label containing the business port.
     *
     * @return port label
     */
    public String getPortLabel() {
        return portLabel;
    }

    /**
     * Sets the label containing the business port.
     *
     * @param portLabel port label
     */
    public void setPortLabel(String portLabel) {
        this.portLabel = portLabel;
    }

    /**
     * Returns the label containing the endpoint protocol.
     *
     * @return protocol label
     */
    public String getProtocolLabel() {
        return protocolLabel;
    }

    /**
     * Sets the label containing the endpoint protocol.
     *
     * @param protocolLabel protocol label
     */
    public void setProtocolLabel(String protocolLabel) {
        this.protocolLabel = protocolLabel;
    }

    /**
     * Returns the fallback business port.
     *
     * @return fallback port, or zero when unset
     */
    public int getInstancePort() {
        return instancePort;
    }

    /**
     * Sets the fallback business port.
     *
     * @param instancePort fallback port
     */
    public void setInstancePort(int instancePort) {
        this.instancePort = instancePort;
    }

    /**
     * Returns the fallback business host.
     *
     * @return fallback host
     */
    public String getInstanceHost() {
        return instanceHost;
    }

    /**
     * Sets the fallback business host.
     *
     * @param instanceHost fallback host
     */
    public void setInstanceHost(String instanceHost) {
        this.instanceHost = instanceHost;
    }

    /**
     * Returns the fallback service name.
     *
     * @return fallback service name
     */
    public String getDefaultServiceName() {
        return defaultServiceName;
    }

    /**
     * Sets the fallback service name.
     *
     * @param defaultServiceName fallback service name
     */
    public void setDefaultServiceName(String defaultServiceName) {
        this.defaultServiceName = defaultServiceName;
    }

    /**
     * Returns whether TLS is enabled for Service Center.
     *
     * @return {@code true} when TLS is enabled
     */
    public boolean isSslEnabled() {
        return sslEnabled;
    }

    /**
     * Enables or disables TLS for Service Center.
     *
     * @param sslEnabled whether TLS is enabled
     */
    public void setSslEnabled(boolean sslEnabled) {
        this.sslEnabled = sslEnabled;
    }
}
