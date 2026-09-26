package com.rxda.nacoscseconfigbridge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import com.rxda.nacoscseconfigbridge.config.KieProperties;
import com.rxda.nacoscseconfigbridge.config.NacosGrpcProperties;
import com.rxda.nacoscseconfigbridge.config.MigrationProperties;
import com.rxda.nacoscseconfigbridge.config.ServiceCenterProperties;

/**
 * Starts the Spring Boot application and registers all bridge configuration properties.
 */
@SpringBootApplication
@EnableConfigurationProperties({
        KieProperties.class,
        NacosGrpcProperties.class,
        MigrationProperties.class,
        ServiceCenterProperties.class
})
public class NacosCseConfigBridgeApplication {

    /**
     * Boots the embedded HTTP and Nacos gRPC endpoints.
     *
     * @param args standard Spring Boot command-line arguments
     */
    static void main(String[] args) {
        SpringApplication.run(NacosCseConfigBridgeApplication.class, args);
    }
}
