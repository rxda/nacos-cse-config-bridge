package com.rxda.nacoscseconfigbridge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * CSE KIE 配置服务的连接和轮询设置。
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
     * 返回逗号分隔的 KIE 服务地址。
     *
     * @return KIE 服务地址
     */
    public String getServerAddr() {
        return serverAddr;
    }

    /**
     * 设置逗号分隔的 KIE 服务地址。
     *
     * @param serverAddr KIE 服务地址
     */
    public void setServerAddr(String serverAddr) {
        this.serverAddr = serverAddr;
    }

    /**
     * 返回用于桥接数据的 KIE 项目。
     *
     * @return KIE 项目名
     */
    public String getProject() {
        return project;
    }

    /**
     * 设置用于桥接数据的 KIE 项目。
     *
     * @param project KIE 项目名
     */
    public void setProject(String project) {
        this.project = project;
    }

    /**
     * 返回会写入 KIE {@code nacos-id} 自定义标签的源 Nacos 实例标识。
     *
     * @return 源 Nacos 实例标识
     */
    public String getApp() {
        return app;
    }

    /**
     * 设置会写入 KIE {@code nacos-id} 自定义标签的源 Nacos 实例标识。
     *
     * @param app 源 Nacos 实例标识
     */
    public void setApp(String app) {
        this.app = app;
    }

    /**
     * 返回 KIE 长轮询的最长等待时间。
     *
     * @return 等待时间（秒）
     */
    public int getPollingWaitSeconds() {
        return pollingWaitSeconds;
    }

    /**
     * 设置 KIE 长轮询的最长等待时间。
     *
     * @param pollingWaitSeconds 等待时间（秒）
     */
    public void setPollingWaitSeconds(int pollingWaitSeconds) {
        this.pollingWaitSeconds = pollingWaitSeconds;
    }

    /**
     * 返回 KIE 套接字超时时间。
     *
     * @return 套接字超时时间（秒）
     */
    public int getSocketTimeoutSeconds() {
        return socketTimeoutSeconds;
    }

    /**
     * 设置 KIE 套接字超时时间。
     *
     * @param socketTimeoutSeconds 套接字超时时间（秒）
     */
    public void setSocketTimeoutSeconds(int socketTimeoutSeconds) {
        this.socketTimeoutSeconds = socketTimeoutSeconds;
    }

    /**
     * 返回 KIE 连接是否启用 TLS。
     *
     * @return 启用 TLS 时返回 {@code true}
     */
    public boolean isSslEnabled() {
        return sslEnabled;
    }

    /**
     * 设置 KIE 连接是否启用 TLS。
     *
     * @param sslEnabled 是否启用 TLS
     */
    public void setSslEnabled(boolean sslEnabled) {
        this.sslEnabled = sslEnabled;
    }
}
