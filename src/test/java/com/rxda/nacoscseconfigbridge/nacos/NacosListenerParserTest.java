package com.rxda.nacoscseconfigbridge.nacos;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NacosListenerParserTest {

    private final NacosListenerParser parser = new NacosListenerParser();

    @Test
    void parsesNacosListeningConfigs() {
        String request = "order.yaml\u0002ORDER_GROUP\u0002dev\u0002abc123\u0001";

        var entries = parser.parse(request);

        assertThat(entries).hasSize(1);
        assertThat(entries.getFirst().key()).isEqualTo(
                new NacosConfigKey("order.yaml", "ORDER_GROUP", "dev"));
        assertThat(entries.getFirst().md5()).isEqualTo("abc123");
    }

    @Test
    void createsNacosChangeResponse() {
        String response = parser.response(
                java.util.List.of(new NacosConfigKey("order.yaml", "ORDER_GROUP", "dev")));

        assertThat(response).isEqualTo("order.yaml\u0002ORDER_GROUP\u0002dev");
    }
}
