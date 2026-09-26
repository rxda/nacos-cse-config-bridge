package com.rxda.nacoscseconfigbridge.nacos;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Exposes shared-watch counts so connection growth is visible in Actuator.
 */
@Component
public class NacosListenerMetrics {

    /**
     * Registers gauges backed by the listener service's live watch counts.
     *
     * @param registry Actuator/Micrometer registry
     * @param listenerService listener service being measured
     */
    public NacosListenerMetrics(MeterRegistry registry, NacosListenerService listenerService) {
        Gauge.builder("nacos.bridge.watch.active", listenerService, NacosListenerService::activeWatchCount)
                .description("Number of unique KIE long-poll watches")
                .register(registry);
        Gauge.builder("nacos.bridge.watch.subscribers", listenerService,
                        NacosListenerService::activeWatchSubscriberCount)
                .description("Number of Nacos subscriptions attached to KIE watches")
                .register(registry);
    }
}
