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
 * Coordinates Nacos listener requests with exact KIE reads and shared long polls.
 */
@Service
public class NacosListenerService {

    private static final Logger LOGGER = LoggerFactory.getLogger(NacosListenerService.class);

    private final KieConfigStore configStore;
    private final ExecutorService listenerExecutor;

    /**
     * One KIE long-poll is enough for a key, even when several Nacos clients
     * listen for that key. Besides reducing KIE load, this also avoids one
     * socket and one virtual task per client/key pair.
     */
    private final Map<NacosConfigKey, SharedWatch> sharedWatches = new ConcurrentHashMap<>();

    /**
     * Creates a listener service backed by the shared listener executor.
     *
     * @param configStore exact KIE-backed configuration store
     * @param listenerExecutor executor for checks and long polls
     */
    public NacosListenerService(
            KieConfigStore configStore,
            @Qualifier("nacosListenerExecutor") ExecutorService listenerExecutor) {
        this.configStore = configStore;
        this.listenerExecutor = listenerExecutor;
    }

    /**
     * Blocks until one subscribed configuration changes or all polls finish.
     *
     * @param entries subscriptions supplied by a Nacos client
     * @return keys whose content changed
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
     * Performs immediate, non-blocking checks for a batch of subscriptions.
     *
     * @param entries subscriptions supplied by a Nacos client
     * @return keys that already differ from the client MD5 values
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
     * Adds a callback to the shared long poll for a configuration key.
     *
     * @param entry subscription and baseline MD5
     * @param onChange callback invoked after a confirmed content change
     * @return handle that removes this subscription
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
     * Returns the number of unique active KIE long polls.
     *
     * @return active shared-watch count
     */
    public int activeWatchCount() {
        return sharedWatches.size();
    }

    /**
     * Returns the number of Nacos subscriptions attached to shared watches.
     *
     * @return active subscription count
     */
    public int activeWatchSubscriberCount() {
        return sharedWatches.values().stream().mapToInt(SharedWatch::subscriberCount).sum();
    }
    /**
     * Checks one subscription without waiting for a KIE revision.
     *
     * @param entry subscription and expected MD5
     * @return key when its content differs from the expected MD5
     */
    private Optional<NacosConfigKey> checkNow(NacosListenerEntry entry) {
        KieConfigStore.ReadResult current = configStore.read(entry.key(), null, false);
        return isChanged(entry, current) ? Optional.of(entry.key()) : Optional.empty();
    }
    /**
     * Performs one immediate check followed by a KIE long poll when necessary.
     *
     * @param entry subscription and expected MD5
     * @return key when its content changes
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
     * Compares a read result with the MD5 supplied by a Nacos client.
     *
     * @param entry subscription containing the expected MD5
     * @param result current KIE read result
     * @return whether the content changed
     */
    private boolean isChanged(NacosListenerEntry entry, KieConfigStore.ReadResult result) {
        return isChanged(entry.md5(), result);
    }
    /**
     * Compares a read result with a previously observed content MD5.
     *
     * @param expectedMd5 previously observed MD5
     * @param result current KIE read result
     * @return whether the content changed
     */
    private boolean isChanged(String expectedMd5, KieConfigStore.ReadResult result) {
        // KIE answers a quiet long poll with 304 and no document body. That is
        // not a deleted Nacos config. Only a successful response can establish
        // the missing-config state; otherwise every polling timeout would push
        // a false change and @NacosValue would refresh continuously.
        if (!result.changed() && result.content().isEmpty()) {
            return false;
        }
        return result.content()
                .map(content -> !Objects.equals(expectedMd5, configStore.md5(content)))
                // An empty md5 is Nacos' representation of a key that is not
                // present. It must not produce a hot loop for missing configs.
                .orElseGet(() -> expectedMd5 != null && !expectedMd5.isBlank());
    }
    /**
     * Removes an unused shared watch and stops its long-poll task.
     *
     * @param watch shared watch to remove
     */
    private void remove(SharedWatch watch) {
        synchronized (sharedWatches) {
            if (watch.subscriberCount() == 0 && sharedWatches.remove(watch.key, watch)) {
                watch.stop();
            }
        }
    }

    /** Owns one KIE long poll shared by all Nacos subscribers for a key. */
    private final class SharedWatch {
        private final NacosConfigKey key;
        private final java.util.Set<Consumer<NacosConfigKey>> subscribers =
                ConcurrentHashMap.newKeySet();
        private final AtomicBoolean active = new AtomicBoolean(true);
        private volatile Future<?> future;
        private volatile String expectedMd5 = "";
        /**
         * Creates a shared watch with the client's current MD5 baseline.
         *
         * @param entry initial subscription
         */
        private SharedWatch(NacosListenerEntry entry) {
            this.key = entry.key();
            this.expectedMd5 = entry.md5();
        }
        /** Starts the asynchronous long-poll task. */
        private void start() {
            future = listenerExecutor.submit(this::run);
        }
        /**
         * Adds one subscriber to this shared watch.
         *
         * @param onChange callback invoked when content changes
         * @return subscription handle
         */
        private Subscription subscribe(Consumer<NacosConfigKey> onChange) {
            subscribers.add(onChange);
            return new Subscription(this, onChange);
        }
        /**
         * Returns the number of active subscribers.
         *
         * @return subscriber count
         */
        private int subscriberCount() {
            return subscribers.size();
        }
        /** Cancels the asynchronous long-poll task. */
        private void stop() {
            if (active.compareAndSet(true, false)) {
                Future<?> current = future;
                if (current != null) {
                    current.cancel(true);
                }
            }
        }
        /** Runs the long-poll loop until the watch is closed. */
        private void run() {
            KieConfigStore.ReadResult current = null;
            while (active.get()) {
                try {
                    if (current == null) {
                        current = configStore.read(key, null, false);
                        // A client that supplied an old md5 must receive the
                        // change immediately; checkNow normally catches this,
                        // but preserving the baseline here closes the race
                        // between the initial check and watch registration.
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
         * Computes the MD5 of the current raw configuration, if present.
         *
         * @param result KIE read result
         * @return content MD5, or an empty string for a missing key
         */
        private String contentMd5(KieConfigStore.ReadResult result) {
            return result.content().map(configStore::md5).orElse("");
        }
        /** Notifies all subscribers after a confirmed content change. */
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

    /** Removes one callback from a shared watch when closed. */
    private final class Subscription implements Watch {
        private final SharedWatch shared;
        private final Consumer<NacosConfigKey> callback;
        private final AtomicBoolean closed = new AtomicBoolean();
        /**
         * Creates a handle for one callback on a shared watch.
         *
         * @param shared shared watch owning the callback
         * @param callback callback to remove when closed
         */
        private Subscription(SharedWatch shared, Consumer<NacosConfigKey> callback) {
            this.shared = shared;
            this.callback = callback;
        }

        /** Closes this resource and releases associated state. */
        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                shared.subscribers.remove(callback);
                remove(shared);
            }
        }
    }

    /** Handle returned to a gRPC connection for cancelling a watch subscription. */
    @FunctionalInterface
    public interface Watch extends AutoCloseable {
        /**
         * Cancels this subscription and releases its shared-watch reference.
         */
        @Override
        void close();
    }
}
