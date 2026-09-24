package com.rxda.nacoscseconfigbridge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Optional registration of Nacos Config clients in the CSE Service Center. */
@ConfigurationProperties(prefix = "srv-nacos-cse-config-bridge.service-center")
public class ServiceCenterProperties {

    /** Disabled by default because registration changes CSE Service Center state. */
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
    private String defaultServiceName;
    private boolean sslEnabled;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getServerAddr() {
        return serverAddr;
    }

    public void setServerAddr(String serverAddr) {
        this.serverAddr = serverAddr;
    }

    public String getProject() {
        return project;
    }

    public void setProject(String project) {
        this.project = project;
    }

    public String getAppId() {
        return appId;
    }

    public void setAppId(String appId) {
        this.appId = appId;
    }

    public String getTenantName() {
        return tenantName;
    }

    public void setTenantName(String tenantName) {
        this.tenantName = tenantName;
    }

    public String getEnvironment() {
        return environment;
    }

    public void setEnvironment(String environment) {
        this.environment = environment;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public long getHeartbeatIntervalSeconds() {
        return heartbeatIntervalSeconds;
    }

    public void setHeartbeatIntervalSeconds(long heartbeatIntervalSeconds) {
        this.heartbeatIntervalSeconds = heartbeatIntervalSeconds;
    }

    public boolean isInstanceEnabled() {
        return instanceEnabled;
    }

    public void setInstanceEnabled(boolean instanceEnabled) {
        this.instanceEnabled = instanceEnabled;
    }

    public String getServiceNameLabel() {
        return serviceNameLabel;
    }

    public void setServiceNameLabel(String serviceNameLabel) {
        this.serviceNameLabel = serviceNameLabel;
    }

    public String getEndpointLabel() {
        return endpointLabel;
    }

    public void setEndpointLabel(String endpointLabel) {
        this.endpointLabel = endpointLabel;
    }

    public String getHostLabel() {
        return hostLabel;
    }

    public void setHostLabel(String hostLabel) {
        this.hostLabel = hostLabel;
    }

    public String getPortLabel() {
        return portLabel;
    }

    public void setPortLabel(String portLabel) {
        this.portLabel = portLabel;
    }

    public String getProtocolLabel() {
        return protocolLabel;
    }

    public void setProtocolLabel(String protocolLabel) {
        this.protocolLabel = protocolLabel;
    }

    public String getDefaultServiceName() {
        return defaultServiceName;
    }

    public void setDefaultServiceName(String defaultServiceName) {
        this.defaultServiceName = defaultServiceName;
    }

    public boolean isSslEnabled() {
        return sslEnabled;
    }

    public void setSslEnabled(boolean sslEnabled) {
        this.sslEnabled = sslEnabled;
    }
}
