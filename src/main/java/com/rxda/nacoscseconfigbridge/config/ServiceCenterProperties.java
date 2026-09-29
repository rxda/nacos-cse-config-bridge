package com.rxda.nacoscseconfigbridge.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 控制是否将 Nacos 客户端可选注册到 CSE 服务中心的设置。
 */
@ConfigurationProperties(prefix = "srv-nacos-cse-config-bridge.service-center")
@Data
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
}
