package com.rxda.nacoscseconfigbridge.config;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

@Configuration
@EnableAsync
public class ExecutorConfiguration {

    @Bean(name = "nacosListenerExecutor", destroyMethod = "close")
    public ExecutorService nacosListenerExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean(name = "nacosGrpcExecutor", destroyMethod = "close")
    public ExecutorService nacosGrpcExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean(name = "nacosServiceCenterExecutor", destroyMethod = "close")
    public ScheduledExecutorService nacosServiceCenterExecutor() {
        return Executors.newScheduledThreadPool(2);
    }
}
