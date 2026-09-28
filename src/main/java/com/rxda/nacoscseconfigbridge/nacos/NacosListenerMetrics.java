package com.rxda.nacoscseconfigbridge.nacos;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * 公开共享监听计数，让连接增长在 Actuator 中可见。
 */
@Component
public class NacosListenerMetrics {

    /**
     * 注册由监听服务实时监听计数支撑的指标。
     *
     * @param registry Actuator/Micrometer 注册表
     * @param listenerService 被统计的监听服务
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
