package com.rxda.nacoscseconfigbridge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 控制是否将 Nacos 客户端可选注册到 CSE 服务中心的设置。
 */
@ConfigurationProperties(prefix = "srv-nacos-cse-config-bridge.service-center")
public class ServiceCenterProperties {

    /** 仅当该开关和服务地址都设置后才尝试注册。 */
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
    /** 可选的业务端口回退值，当 Nacos 客户端未发送 servicePort 时使用。 */
    private int instancePort;
    /** 可选的业务主机回退值；为空时使用 gRPC 对端地址。 */
    private String instanceHost;
    private String defaultServiceName;
    private boolean sslEnabled;

    /**
     * 返回是否启用服务中心注册。
     *
     * @return 启用注册时返回 {@code true}
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 启用或禁用服务中心注册。
     *
     * @param enabled 是否启用注册
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * 返回逗号分隔的服务中心地址。
     *
     * @return 服务中心地址
     */
    public String getServerAddr() {
        return serverAddr;
    }

    /**
     * 设置逗号分隔的服务中心地址。
     *
     * @param serverAddr 服务中心地址
     */
    public void setServerAddr(String serverAddr) {
        this.serverAddr = serverAddr;
    }

    /**
     * 返回 CSE 项目。
     *
     * @return CSE 项目名
     */
    public String getProject() {
        return project;
    }

    /**
     * 设置 CSE 项目。
     *
     * @param project CSE 项目名
     */
    public void setProject(String project) {
        this.project = project;
    }

    /**
     * 返回服务中心租户。
     *
     * @return 租户名
     */
    public String getTenantName() {
        return tenantName;
    }

    /**
     * 设置服务中心租户。
     *
     * @param tenantName 租户名
     */
    public void setTenantName(String tenantName) {
        this.tenantName = tenantName;
    }

    /**
     * 返回 CSE 应用标识。
     *
     * @return 应用标识
     */
    public String getAppId() {
        return appId;
    }

    /**
     * 设置 CSE 应用标识。
     *
     * @param appId 应用标识
     */
    public void setAppId(String appId) {
        this.appId = appId;
    }

    /**
     * 返回回退的 CSE 环境。
     *
     * @return 环境名
     */
    public String getEnvironment() {
        return environment;
    }

    /**
     * 设置回退的 CSE 环境。
     *
     * @param environment 环境名
     */
    public void setEnvironment(String environment) {
        this.environment = environment;
    }

    /**
     * 返回上报给服务中心的微服务版本。
     *
     * @return 服务版本
     */
    public String getVersion() {
        return version;
    }

    /**
     * 设置上报给服务中心的微服务版本。
     *
     * @param version 服务版本
     */
    public void setVersion(String version) {
        this.version = version;
    }

    /**
     * 返回实例心跳间隔。
     *
     * @return 心跳间隔（秒）
     */
    public long getHeartbeatIntervalSeconds() {
        return heartbeatIntervalSeconds;
    }

    /**
     * 设置实例心跳间隔。
     *
     * @param heartbeatIntervalSeconds 心跳间隔（秒）
     */
    public void setHeartbeatIntervalSeconds(long heartbeatIntervalSeconds) {
        this.heartbeatIntervalSeconds = heartbeatIntervalSeconds;
    }

    /**
     * 返回是否应注册业务实例。
     *
     * @return 启用实例注册时返回 {@code true}
     */
    public boolean isInstanceEnabled() {
        return instanceEnabled;
    }

    /**
     * 启用或禁用业务实例注册。
     *
     * @param instanceEnabled 是否启用实例注册
     */
    public void setInstanceEnabled(boolean instanceEnabled) {
        this.instanceEnabled = instanceEnabled;
    }

    /**
     * 返回包含 Nacos 应用或服务名的标签。
     *
     * @return 服务名标签
     */
    public String getServiceNameLabel() {
        return serviceNameLabel;
    }

    /**
     * 设置包含 Nacos 应用或服务名的标签。
     *
     * @param serviceNameLabel 服务名标签
     */
    public void setServiceNameLabel(String serviceNameLabel) {
        this.serviceNameLabel = serviceNameLabel;
    }

    /**
     * 返回包含完整端点 URI 的标签。
     *
     * @return 端点标签；使用独立主机和端口标签时为空
     */
    public String getEndpointLabel() {
        return endpointLabel;
    }

    /**
     * 设置包含完整端点 URI 的标签。
     *
     * @param endpointLabel 端点标签
     */
    public void setEndpointLabel(String endpointLabel) {
        this.endpointLabel = endpointLabel;
    }

    /**
     * 返回包含业务主机的标签。
     *
     * @return 主机标签
     */
    public String getHostLabel() {
        return hostLabel;
    }

    /**
     * 设置包含业务主机的标签。
     *
     * @param hostLabel 主机标签
     */
    public void setHostLabel(String hostLabel) {
        this.hostLabel = hostLabel;
    }

    /**
     * 返回包含业务端口的标签。
     *
     * @return 端口标签
     */
    public String getPortLabel() {
        return portLabel;
    }

    /**
     * 设置包含业务端口的标签。
     *
     * @param portLabel 端口标签
     */
    public void setPortLabel(String portLabel) {
        this.portLabel = portLabel;
    }

    /**
     * 返回包含端点协议的标签。
     *
     * @return 协议标签
     */
    public String getProtocolLabel() {
        return protocolLabel;
    }

    /**
     * 设置包含端点协议的标签。
     *
     * @param protocolLabel 协议标签
     */
    public void setProtocolLabel(String protocolLabel) {
        this.protocolLabel = protocolLabel;
    }

    /**
     * 返回回退的业务端口。
     *
     * @return 回退端口，未设置时为 0
     */
    public int getInstancePort() {
        return instancePort;
    }

    /**
     * 设置回退的业务端口。
     *
     * @param instancePort 回退端口
     */
    public void setInstancePort(int instancePort) {
        this.instancePort = instancePort;
    }

    /**
     * 返回回退的业务主机。
     *
     * @return 回退主机
     */
    public String getInstanceHost() {
        return instanceHost;
    }

    /**
     * 设置回退的业务主机。
     *
     * @param instanceHost 回退主机
     */
    public void setInstanceHost(String instanceHost) {
        this.instanceHost = instanceHost;
    }

    /**
     * 返回回退的服务名。
     *
     * @return 回退服务名
     */
    public String getDefaultServiceName() {
        return defaultServiceName;
    }

    /**
     * 设置回退的服务名。
     *
     * @param defaultServiceName 回退服务名
     */
    public void setDefaultServiceName(String defaultServiceName) {
        this.defaultServiceName = defaultServiceName;
    }

    /**
     * 返回服务中心是否启用 TLS。
     *
     * @return 启用 TLS 时返回 {@code true}
     */
    public boolean isSslEnabled() {
        return sslEnabled;
    }

    /**
     * 启用或禁用服务中心的 TLS。
     *
     * @param sslEnabled 是否启用 TLS
     */
    public void setSslEnabled(boolean sslEnabled) {
        this.sslEnabled = sslEnabled;
    }
}
