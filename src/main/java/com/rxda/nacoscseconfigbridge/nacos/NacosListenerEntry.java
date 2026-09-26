package com.rxda.nacoscseconfigbridge.nacos;

/**
 * One Nacos listener subscription and the content digest last observed by the client.
 *
 * @param key configuration identity
 * @param md5 client-observed content MD5
 */
public record NacosListenerEntry(NacosConfigKey key, String md5) {
}
