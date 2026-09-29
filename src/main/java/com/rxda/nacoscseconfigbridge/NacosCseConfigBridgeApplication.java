package com.rxda.nacoscseconfigbridge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import com.rxda.nacoscseconfigbridge.config.KieProperties;
import com.rxda.nacoscseconfigbridge.config.NacosAuthProperties;
import com.rxda.nacoscseconfigbridge.config.NacosGrpcProperties;
import com.rxda.nacoscseconfigbridge.config.MigrationProperties;
import com.rxda.nacoscseconfigbridge.config.ServiceCenterProperties;

/**
 * 启动 Spring Boot 应用，并注册桥接的所有配置属性。
 */
@SpringBootApplication
@EnableConfigurationProperties({
        KieProperties.class,
        NacosAuthProperties.class,
        NacosGrpcProperties.class,
        MigrationProperties.class,
        ServiceCenterProperties.class
})
public class NacosCseConfigBridgeApplication {

    /**
     * 启动内嵌 HTTP 和 Nacos gRPC 端点。
     *
     * @param args 标准的 Spring Boot 命令行参数
     */
    static void main(String[] args) {
        SpringApplication.run(NacosCseConfigBridgeApplication.class, args);
    }
}
