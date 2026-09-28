package com.rxda.nacoscseconfigbridge.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * 创建协议处理、长轮询和心跳所用的执行器。
 */
@Configuration
@EnableAsync
public class ExecutorConfiguration {
    /**
     * 为 Nacos HTTP 监听请求创建虚拟线程执行器。
     *
     * @return 监听任务执行器
     */
    @Bean(name = "nacosListenerExecutor", destroyMethod = "close")
    public ExecutorService nacosListenerExecutor() {
        return Executors.newThreadPerTaskExecutor(virtualThreads("nacos-listener-"));
    }
    /**
     * 为 Nacos gRPC 回调创建虚拟线程执行器。
     *
     * @return gRPC 任务执行器
     */
    @Bean(name = "nacosGrpcExecutor", destroyMethod = "close")
    public ExecutorService nacosGrpcExecutor() {
        return Executors.newThreadPerTaskExecutor(virtualThreads("nacos-grpc-"));
    }
    /**
     * 创建用于服务中心心跳的小型平台线程调度器。
     *
     * @return 心跳调度器
     */
    @Bean(name = "nacosServiceCenterExecutor", destroyMethod = "close")
    public ScheduledExecutorService nacosServiceCenterExecutor() {
        // 心跳是短小的周期性任务。使用小型的平台线程调度器，
        // 而不是为每个客户端创建无界的调度器。
        return Executors.newScheduledThreadPool(2,
                Thread.ofPlatform().name("cse-heartbeat-", 0).factory());
    }

    /**
     * 创建带名称的虚拟线程工厂。
     *
     * @param prefix 线程名前缀
     * @return 虚拟线程工厂
     */
    private static ThreadFactory virtualThreads(String prefix) {
        return Thread.ofVirtual().name(prefix, 0).factory();
    }
}
