package com.rxda.nacoscseconfigbridge.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Creates the executors used by protocol handling, long polling, and heartbeats.
 */
@Configuration
@EnableAsync
public class ExecutorConfiguration {
    /**
     * Creates a virtual-thread executor for Nacos HTTP listener requests.
     *
     * @return executor for listener tasks
     */
    @Bean(name = "nacosListenerExecutor", destroyMethod = "close")
    public ExecutorService nacosListenerExecutor() {
        return Executors.newThreadPerTaskExecutor(virtualThreads("nacos-listener-"));
    }
    /**
     * Creates a virtual-thread executor for Nacos gRPC callbacks.
     *
     * @return executor for gRPC tasks
     */
    @Bean(name = "nacosGrpcExecutor", destroyMethod = "close")
    public ExecutorService nacosGrpcExecutor() {
        return Executors.newThreadPerTaskExecutor(virtualThreads("nacos-grpc-"));
    }
    /**
     * Creates the small platform-thread scheduler used for Service Center heartbeats.
     *
     * @return heartbeat scheduler
     */
    @Bean(name = "nacosServiceCenterExecutor", destroyMethod = "close")
    public ScheduledExecutorService nacosServiceCenterExecutor() {
        // Heartbeats are short periodic tasks. Keep a small platform-thread
        // scheduler instead of creating an unbounded scheduler per client.
        return Executors.newScheduledThreadPool(2,
                Thread.ofPlatform().name("cse-heartbeat-", 0).factory());
    }

    /**
     * Creates a named virtual-thread factory.
     *
     * @param prefix thread name prefix
     * @return virtual-thread factory
     */
    private static ThreadFactory virtualThreads(String prefix) {
        return Thread.ofVirtual().name(prefix, 0).factory();
    }
}
