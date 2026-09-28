package com.rxda.nacoscseconfigbridge.cse;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.rxda.nacoscseconfigbridge.config.KieClientFactory;
import com.rxda.nacoscseconfigbridge.config.KieProperties;
import com.rxda.nacoscseconfigbridge.nacos.NacosConfigKey;

class KieConfigStoreTest {

    @Test
    void queriesCseLabelsUsingTheNewNacosMapping() {
        RecordingClientFactory clientFactory = new RecordingClientFactory("nacos-a");

        KieConfigStore store = new KieConfigStore(clientFactory);
        store.read(new NacosConfigKey("order.yaml", "ORDER_GROUP", "tenant-a"), null, false);

        assertThat(clientFactory.labelsQuery).isEqualTo(
                "label=app%3Atenant-a&label=environment%3AORDER_GROUP"
                + "&label=service%3Aorder.yaml&label=nacos-id%3Anacos-a");
    }

    private static final class RecordingClientFactory extends KieClientFactory {
        private String nacosId;
        private String labelsQuery;

        private RecordingClientFactory(String nacosId) {
            super(properties(), List.of());
            this.nacosId = nacosId;
        }

        @Override
        public String app() {
            return nacosId;
        }

        @Override
        public RawQuery queryRaw(String labelsQuery, String revision, boolean longPolling) {
            this.labelsQuery = labelsQuery;
            return new RawQuery(false, "-1", List.of());
        }

        private static KieProperties properties() {
            KieProperties properties = new KieProperties();
            properties.setServerAddr("http://cse:30110");
            properties.setProject("default");
            return properties;
        }
    }
}
