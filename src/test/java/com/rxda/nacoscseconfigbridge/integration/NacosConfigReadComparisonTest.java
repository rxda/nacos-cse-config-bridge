package com.rxda.nacoscseconfigbridge.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.config.ConfigFactory;
import com.alibaba.nacos.api.config.ConfigService;

/**
 * Optional black-box comparison program. It is disabled unless explicitly
 * enabled because it talks to external Nacos and proxy processes.
 *
 * Run with:
 * mvn -Dtest=NacosConfigReadComparisonTest -Dnacos.comparison.enabled=true \
 *   -Dnacos.source.addr=127.0.0.1:8848 -Dnacos.proxy.addr=127.0.0.1:8080 \
 *   -Dnacos.data-id=application.yaml -Dnacos.group=DEFAULT_GROUP test
 */
@EnabledIfSystemProperty(named = "nacos.comparison.enabled", matches = "true")
class NacosConfigReadComparisonTest {

    @Test
    void sourceNacosAndProxyReturnTheSameConfig() throws Exception {
        String sourceAddress = System.getProperty("nacos.source.addr", "127.0.0.1:8848");
        String proxyAddress = System.getProperty("nacos.proxy.addr", "127.0.0.1:8080");
        String dataId = System.getProperty("nacos.data-id", "application.yaml");
        String group = System.getProperty("nacos.group", "DEFAULT_GROUP");
        String namespace = System.getProperty("nacos.namespace", "");

        ConfigService source = create(sourceAddress, namespace);
        ConfigService proxy = create(proxyAddress, namespace);
        try {
            String sourceContent = source.getConfig(dataId, group, 10_000);
            String proxyContent = proxy.getConfig(dataId, group, 10_000);
            assertThat(sourceContent).as("source Nacos config").isNotNull();
            assertThat(proxyContent).as("proxy config").isEqualTo(sourceContent);
        } finally {
            proxy.shutDown();
            source.shutDown();
        }
    }

    private ConfigService create(String address, String namespace) throws Exception {
        Properties properties = new Properties();
        properties.setProperty(PropertyKeyConst.SERVER_ADDR, address);
        properties.setProperty(PropertyKeyConst.CONFIG_REQUEST_TIMEOUT, "-1");
        String connectionLabels = System.getProperty("nacos.app.conn.labels");
        if (connectionLabels != null && !connectionLabels.isBlank()) {
            properties.setProperty("nacos.app.conn.labels", connectionLabels);
        }
        String username = System.getProperty("nacos.username");
        String password = System.getProperty("nacos.password");
        if (username != null && !username.isBlank() && password != null) {
            properties.setProperty(PropertyKeyConst.USERNAME, username);
            properties.setProperty(PropertyKeyConst.PASSWORD, password);
        }
        if (namespace != null && !namespace.isBlank()) {
            properties.setProperty(PropertyKeyConst.NAMESPACE, namespace);
        }
        return ConfigFactory.createConfigService(properties);
    }
}
