package com.rxda.nacoscseconfigbridge.nacos;

/**
 * 一个 Nacos 监听订阅，以及客户端最近一次观察到的内容摘要。
 *
 * @param key 配置标识
 * @param md5 客户端观察到的内容 MD5
 */
public record NacosListenerEntry(NacosConfigKey key, String md5) {
}
