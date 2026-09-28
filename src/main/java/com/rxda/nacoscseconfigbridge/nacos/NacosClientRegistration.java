package com.rxda.nacoscseconfigbridge.nacos;

import java.util.Map;

/**
 * 将 Nacos 协议客户端作为可选的 CSE 服务中心观察者进行注册。
 */
public interface NacosClientRegistration {

    /**
     * 注册一个 Nacos 客户端连接。
     *
     * @param tenant 连接建立时传来的 Nacos 租户
     * @param labels 客户端连接标签
     * @param remoteHost 网桥观察到的对端地址
     * @return 客户端注册句柄，关闭时注销该客户端
     */
    Registration register(String tenant, Map<String, String> labels, String remoteHost);

    /** 释放客户端注册的句柄。 */
    interface Registration extends AutoCloseable {
        /**
         * 释放该句柄代表的服务中心注册。
         */
        @Override
        void close();
    }

    NacosClientRegistration NOOP = (tenant, labels, remoteHost) -> () -> {
    };
}
