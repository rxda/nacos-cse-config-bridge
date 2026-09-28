package com.rxda.nacoscseconfigbridge.nacos;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.springframework.beans.factory.annotation.Qualifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.rxda.nacoscseconfigbridge.cse.KieConfigStore;

/**
 * 用精确 KIE 读取和共享长轮询协调 Nacos 监听请求。
 */
@Service
public class NacosListenerService {

    private static final Logger LOGGER = LoggerFactory.getLogger(NacosListenerService.class);

    private final KieConfigStore configStore;
    private final ExecutorService listenerExecutor;

    /**
     * 一个 key 只需要一次 KIE 长轮询，即使有多个 Nacos 客户端在监听它。
     * 除了降低 KIE 负载，也避免了每个 client/key 对占用一个 socket
     * 和一个虚拟任务。
     */
    private final Map<NacosConfigKey, SharedWatch> sharedWatches = new ConcurrentHashMap<>();

    /**
     * 创建由共享监听线程池支撑的监听服务。
     *
     * @param configStore 精确的 KIE 配置存储
     * @param listenerExecutor 检查和长轮询用的线程池
     */
    public NacosListenerService(
            KieConfigStore configStore,
            @Qualifier("nacosListenerExecutor") ExecutorService listenerExecutor) {
        this.configStore = configStore;
        this.listenerExecutor = listenerExecutor;
    }

    /**
     * 阻塞等待，直到某个订阅的配置变更或所有轮询结束。
     *
     * @param entries Nacos 客户端提交的订阅
     * @return 内容变更的键
     */
    public List<NacosConfigKey> listen(List<NacosListenerEntry> entries) {
        if (entries.isEmpty()) {
            return List.of();
        }
        ExecutorCompletionService<Optional<NacosConfigKey>> completionService =
                new ExecutorCompletionService<>(listenerExecutor);
        List<Future<Optional<NacosConfigKey>>> futures = entries.stream()
                .map(entry -> completionService.submit(() -> waitForChange(entry)))
                .toList();
        try {
            for (int i = 0; i < entries.size(); i++) {
                Optional<NacosConfigKey> result = completionService.take().get();
                if (result.isPresent()) {
                    // Nacos 会刷新这个 key 并重新提交剩下的 key。
                    // 直接返回可以避免一个无关的长轮询拖慢真实的变更通知。
                    return List.of(result.get());
                }
            }
            return List.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for KIE configuration", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Unable to read KIE configuration", e.getCause());
        } finally {
            for (Future<Optional<NacosConfigKey>> future : futures) {
                future.cancel(true);
            }
        }
    }

    /**
     * 对一批订阅做立即的、非阻塞的检查。
     *
     * @param entries Nacos 客户端提交的订阅
     * @return 已与客户端 MD5 不同的键
     */
    public List<NacosConfigKey> checkNow(List<NacosListenerEntry> entries) {
        if (entries.isEmpty()) {
            return List.of();
        }
        ExecutorCompletionService<Optional<NacosConfigKey>> completionService =
                new ExecutorCompletionService<>(listenerExecutor);
        List<Future<Optional<NacosConfigKey>>> futures = entries.stream()
                .map(entry -> completionService.submit(() -> checkNow(entry)))
                .toList();
        List<NacosConfigKey> changed = new ArrayList<>();
        try {
            for (int i = 0; i < entries.size(); i++) {
                completionService.take().get().ifPresent(changed::add);
            }
            return List.copyOf(changed);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while checking KIE configuration", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Unable to read KIE configuration", e.getCause());
        } finally {
            for (Future<Optional<NacosConfigKey>> future : futures) {
                future.cancel(true);
            }
        }
    }

    /**
     * 把一个回调加到某配置 key 的共享长轮询上。
     *
     * @param entry 订阅和基线 MD5
     * @param onChange 内容变更确认后调用的回调
     * @return 移除该订阅的句柄
     */
    public Watch startWatch(NacosListenerEntry entry, Consumer<NacosConfigKey> onChange) {
        Objects.requireNonNull(entry, "entry");
        Objects.requireNonNull(onChange, "onChange");
        SharedWatch shared;
        Subscription subscription;
        synchronized (sharedWatches) {
            shared = sharedWatches.get(entry.key());
            if (shared == null) {
                shared = new SharedWatch(entry);
                sharedWatches.put(entry.key(), shared);
                subscription = shared.subscribe(onChange);
                shared.start();
                return subscription;
            }
            subscription = shared.subscribe(onChange);
        }
        return subscription;
    }

    /**
     * 返回唯一活跃 KIE 长轮询的数量。
     *
     * @return 活跃共享监听数
     */
    public int activeWatchCount() {
        return sharedWatches.size();
    }

    /**
     * 返回挂在共享监听上的 Nacos 订阅数。
     *
     * @return 活跃订阅数
     */
    public int activeWatchSubscriberCount() {
        return sharedWatches.values().stream().mapToInt(SharedWatch::subscriberCount).sum();
    }
    /**
     * 检查一个订阅，不等待 KIE 版本变化。
     *
     * @param entry 订阅和期望的 MD5
     * @return 内容与期望 MD5 不同时返回该 key
     */
    private Optional<NacosConfigKey> checkNow(NacosListenerEntry entry) {
        KieConfigStore.ReadResult current = configStore.read(entry.key(), null, false);
        return isChanged(entry, current) ? Optional.of(entry.key()) : Optional.empty();
    }
    /**
     * 先做一次立即检查，必要时再发起 KIE 长轮询。
     *
     * @param entry 订阅和期望的 MD5
     * @return 内容变更时返回该 key
     */
    private Optional<NacosConfigKey> waitForChange(NacosListenerEntry entry) {
        KieConfigStore.ReadResult current = configStore.read(entry.key(), null, false);
        if (isChanged(entry, current)) {
            return Optional.of(entry.key());
        }

        KieConfigStore.ReadResult waited = configStore.read(entry.key(), current.revision(), true);
        return isChanged(entry, waited) ? Optional.of(entry.key()) : Optional.empty();
    }
    /**
     * 把读取结果与 Nacos 客户端提供的 MD5 对比。
     *
     * @param entry 包含期望 MD5 的订阅
     * @param result 当前 KIE 读取结果
     * @return 内容是否变更
     */
    private boolean isChanged(NacosListenerEntry entry, KieConfigStore.ReadResult result) {
        return isChanged(entry.md5(), result);
    }
    /**
     * 把读取结果与之前观察到的内容 MD5 对比。
     *
     * @param expectedMd5 之前观察到的 MD5
     * @param result 当前 KIE 读取结果
     * @return 内容是否变更
     */
    private boolean isChanged(String expectedMd5, KieConfigStore.ReadResult result) {
        // KIE 对无变化的长轮询返回 304 且没有文档体。
        // 这不代表 Nacos 配置被删了。只有成功的响应才能确定
        // "配置缺失" 状态，否则每次轮询超时都会推送一次虚假变更，
        // @NacosValue 会不断刷新。
        if (!result.changed() && result.content().isEmpty()) {
            return false;
        }
        return result.content()
                .map(content -> !Objects.equals(expectedMd5, configStore.md5(content)))
                // 空 md5 是 Nacos 对"键不存在"的表示。
                // 缺失配置不能进入热循环。
                .orElseGet(() -> expectedMd5 != null && !expectedMd5.isBlank());
    }
    /**
     * 移除不再使用的共享监听，并停止它的长轮询任务。
     *
     * @param watch 要移除的共享监听
     */
    private void remove(SharedWatch watch) {
        synchronized (sharedWatches) {
            if (watch.subscriberCount() == 0 && sharedWatches.remove(watch.key, watch)) {
                watch.stop();
            }
        }
    }

    /** 为一个 key 拥有一个 KIE 长轮询，供所有 Nacos 订阅者共享。 */
    private final class SharedWatch {
        private final NacosConfigKey key;
        private final java.util.Set<Consumer<NacosConfigKey>> subscribers =
                ConcurrentHashMap.newKeySet();
        private final AtomicBoolean active = new AtomicBoolean(true);
        private volatile Future<?> future;
        private volatile String expectedMd5 = "";
        /**
         * 创建共享监听，以客户端当前 MD5 为基线。
         *
         * @param entry 初始订阅
         */
        private SharedWatch(NacosListenerEntry entry) {
            this.key = entry.key();
            this.expectedMd5 = entry.md5();
        }
        /** 启动异步长轮询任务。 */
        private void start() {
            future = listenerExecutor.submit(this::run);
        }
        /**
         * 给该共享监听添加一个订阅者。
         *
         * @param onChange 内容变更时调用的回调
         * @return 订阅句柄
         */
        private Subscription subscribe(Consumer<NacosConfigKey> onChange) {
            subscribers.add(onChange);
            return new Subscription(this, onChange);
        }
        /**
         * 返回活跃订阅者数量。
         *
         * @return 订阅者数
         */
        private int subscriberCount() {
            return subscribers.size();
        }
        /** 取消异步长轮询任务。 */
        private void stop() {
            if (active.compareAndSet(true, false)) {
                Future<?> current = future;
                if (current != null) {
                    current.cancel(true);
                }
            }
        }
        /** 运行长轮询循环，直到监听被关闭。 */
        private void run() {
            KieConfigStore.ReadResult current = null;
            while (active.get()) {
                try {
                    if (current == null) {
                        current = configStore.read(key, null, false);
                        // 客户端如果传了旧 md5，必须立即收到变更；
                        // 正常情况下 checkNow 已经处理了，但在这里保留基线
                        // 可以关闭"初始检查"和"监听注册"之间的竞态窗口。
                        if (isChanged(expectedMd5, current)) {
                            notifySubscribers();
                        }
                        expectedMd5 = contentMd5(current);
                    }

                    KieConfigStore.ReadResult next = configStore.read(key, current.revision(), true);
                    if (!active.get()) {
                        return;
                    }
                    if (isChanged(expectedMd5, next)) {
                        expectedMd5 = contentMd5(next);
                        notifySubscribers();
                    }
                    current = next;
                } catch (RuntimeException e) {
                    if (!active.get()) {
                        return;
                    }
                    LOGGER.warn("Unable to watch KIE configuration {} {}", key.dataId(),
                            key.effectiveGroup(), e);
                    current = null;
                    try {
                        TimeUnit.SECONDS.sleep(1);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
        /**
         * 计算当前原始配置的 MD5（如果存在）。
         *
         * @param result KIE 读取结果
         * @return 内容 MD5，key 不存在时返回空字符串
         */
        private String contentMd5(KieConfigStore.ReadResult result) {
            return result.content().map(configStore::md5).orElse("");
        }
        /** 内容变更确认后通知所有订阅者。 */
        private void notifySubscribers() {
            for (Consumer<NacosConfigKey> subscriber : subscribers) {
                try {
                    if (active.get()) {
                        subscriber.accept(key);
                    }
                } catch (RuntimeException e) {
                    LOGGER.debug("Nacos watch subscriber failed for {}", key.dataId(), e);
                }
            }
        }
    }

    /** 关闭时从共享监听移除一个回调。 */
    private final class Subscription implements Watch {
        private final SharedWatch shared;
        private final Consumer<NacosConfigKey> callback;
        private final AtomicBoolean closed = new AtomicBoolean();
        /**
         * 为共享监听上的一个回调创建句柄。
         *
         * @param shared 拥有该回调的共享监听
         * @param callback 关闭时移除的回调
         */
        private Subscription(SharedWatch shared, Consumer<NacosConfigKey> callback) {
            this.shared = shared;
            this.callback = callback;
        }

        /** 关闭该资源并释放关联状态。 */
        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                shared.subscribers.remove(callback);
                remove(shared);
            }
        }
    }

    /** 返回给 gRPC 连接的句柄，用于取消监听订阅。 */
    @FunctionalInterface
    public interface Watch extends AutoCloseable {
        /**
         * 取消该订阅并释放它对共享监听的引用。
         */
        @Override
        void close();
    }
}
