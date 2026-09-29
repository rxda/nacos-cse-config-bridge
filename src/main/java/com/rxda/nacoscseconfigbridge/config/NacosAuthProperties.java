package com.rxda.nacoscseconfigbridge.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Nacos 兼容接口的鉴权设置。
 */
@ConfigurationProperties(prefix = "srv-nacos-cse-config-bridge.auth")
@Data
public class NacosAuthProperties {

    /** 是否要求配置读取和监听请求携带有效令牌。 */
    private boolean enabled;
    /** 是否接受任意非空用户名和密码。 */
    private boolean acceptAnyCredentials;
    /** 严格模式下允许登录的用户名。 */
    private String username = "nacos";
    /** 严格模式下允许登录的密码。 */
    private String password = "";
    /** 登录令牌的有效期，单位为秒。 */
    private long tokenTtlSeconds = 18000;
    /** 所有副本共享的无状态令牌签名密钥。 */
    private String tokenSecret = "";
}
