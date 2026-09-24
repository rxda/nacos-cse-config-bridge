package com.rxda.nacoscseconfigbridge.nacos;

import java.util.List;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Optional;
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

@Service
public class NacosListenerService {

    private static final Logger LOGGER = LoggerFactory.getLogger(NacosListenerService.class);

    private final KieConfigStore configStore;
    private final ExecutorService listenerExecutor;

    public NacosListenerService(
            KieConfigStore configStore,
            @Qualifier("nacosListenerExecutor") ExecutorService listenerExecutor) {
        this.configStore = configStore;
        this.listenerExecutor = listenerExecutor;
    }

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
                    // Nacos will refresh this key and submit the remaining
                    // keys again. Returning now prevents an unrelated long
                    // poll from delaying a real change notification.
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
     * Performs a single non-blocking check for all keys. gRPC uses this for the
     * unary response and then starts active watches for keys that are unchanged.
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
     * Starts a KIE watch for one key. The returned handle must be closed when the
     * client connection is replaced or disconnected.
     */
    public Watch startWatch(NacosListenerEntry entry, Consumer<NacosConfigKey> onChange) {
        AtomicBoolean active = new AtomicBoolean(true);
        Future<?> future = listenerExecutor.submit(() -> watch(entry, onChange, active));
        return () -> {
            if (active.compareAndSet(true, false)) {
                future.cancel(true);
            }
        };
    }

    private Optional<NacosConfigKey> checkNow(NacosListenerEntry entry) {
        KieConfigStore.ReadResult current = configStore.read(entry.key(), null, false);
        return isChanged(entry, current) ? Optional.of(entry.key()) : Optional.empty();
    }

    private void watch(NacosListenerEntry entry, Consumer<NacosConfigKey> onChange, AtomicBoolean active) {
        String expectedMd5 = entry.md5();
        KieConfigStore.ReadResult current = null;
        while (active.get()) {
            try {
                if (current == null) {
                    current = configStore.read(entry.key(), null, false);
                }
                if (isChanged(expectedMd5, current)) {
                    if (active.get()) {
                        onChange.accept(entry.key());
                    }
                    expectedMd5 = current.content().map(configStore::md5).orElse("");
                    current = configStore.read(entry.key(), current.revision(), false);
                    continue;
                }

                String revision = current.revision();
                current = configStore.read(entry.key(), revision, true);
                if (!active.get()) {
                    return;
                }
                if (isChanged(expectedMd5, current)) {
                    onChange.accept(entry.key());
                    expectedMd5 = current.content().map(configStore::md5).orElse("");
                }
            } catch (RuntimeException e) {
                if (!active.get()) {
                    return;
                }
                LOGGER.warn("Unable to watch KIE configuration {} {}", entry.key().dataId(),
                        entry.key().effectiveGroup(), e);
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

    private Optional<NacosConfigKey> waitForChange(NacosListenerEntry entry) {
        KieConfigStore.ReadResult current = configStore.read(entry.key(), null, false);
        if (isChanged(entry, current)) {
            return Optional.of(entry.key());
        }

        KieConfigStore.ReadResult waited = configStore.read(entry.key(), current.revision(), true);
        return isChanged(entry, waited) ? Optional.of(entry.key()) : Optional.empty();
    }

    private boolean isChanged(NacosListenerEntry entry, KieConfigStore.ReadResult result) {
        return isChanged(entry.md5(), result);
    }

    private boolean isChanged(String expectedMd5, KieConfigStore.ReadResult result) {
        return result.content()
                .map(content -> !Objects.equals(expectedMd5, configStore.md5(content)))
                // An empty md5 is Nacos' representation of a key that is not
                // present. It must not produce a hot loop for missing configs.
                .orElseGet(() -> expectedMd5 != null && !expectedMd5.isBlank());
    }

    @FunctionalInterface
    public interface Watch extends AutoCloseable {
        @Override
        void close();
    }
}
