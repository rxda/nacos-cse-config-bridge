package com.rxda.nacoscseconfigbridge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import com.rxda.nacoscseconfigbridge.config.KieProperties;
import com.rxda.nacoscseconfigbridge.config.NacosGrpcProperties;
import com.rxda.nacoscseconfigbridge.config.MigrationProperties;
import com.rxda.nacoscseconfigbridge.config.ServiceCenterProperties;

@SpringBootApplication
@EnableConfigurationProperties({
        KieProperties.class,
        NacosGrpcProperties.class,
        MigrationProperties.class,
        ServiceCenterProperties.class
})
public class NacosCseConfigBridgeApplication {

    static void main(String[] args) {
        SpringApplication.run(NacosCseConfigBridgeApplication.class, args);
    }
}
