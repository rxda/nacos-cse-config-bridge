package com.rxda.nacoscseconfigbridge.nacos;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.ServerSocket;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.config.remote.request.ConfigQueryRequest;
import com.alibaba.nacos.api.config.remote.response.ConfigQueryResponse;
import com.alibaba.nacos.api.config.ConfigFactory;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;
import com.alibaba.nacos.api.grpc.auto.Payload;
import com.alibaba.nacos.api.grpc.auto.BiRequestStreamGrpc;
import com.alibaba.nacos.api.grpc.auto.RequestGrpc;
import com.alibaba.nacos.api.remote.request.ConnectionSetupRequest;
import com.alibaba.nacos.api.remote.request.SetupAckRequest;
import com.alibaba.nacos.api.remote.response.ServerCheckResponse;
import com.alibaba.nacos.common.remote.client.grpc.GrpcUtils;
import com.alibaba.nacos.shaded.io.grpc.ManagedChannel;
import com.alibaba.nacos.shaded.io.grpc.ManagedChannelBuilder;
import com.alibaba.nacos.shaded.io.grpc.Status;
import com.alibaba.nacos.shaded.io.grpc.stub.StreamObserver;

import com.rxda.nacoscseconfigbridge.config.NacosGrpcProperties;
import com.rxda.nacoscseconfigbridge.config.NacosAuthProperties;
import com.rxda.nacoscseconfigbridge.auth.NacosAuthService;
import com.rxda.nacoscseconfigbridge.cse.KieConfigStore;
import com.rxda.nacoscseconfigbridge.nacos.NacosConfigKey;
import com.rxda.nacoscseconfigbridge.nacos.NacosClientRegistration;
import com.rxda.nacoscseconfigbridge.nacos.NacosGrpcServer;
import com.rxda.nacoscseconfigbridge.nacos.NacosListenerService;

class NacosGrpcServerTest {

    private final Object contentLock = new Object();
    private final AtomicInteger revisionCounter = new AtomicInteger(17);
    private final CountDownLatch longPollStarted = new CountDownLatch(1);
    private String content = "feature.enabled: true\n";
    private final KieConfigStore configStore = new KieConfigStore() {
        @Override
        public ReadResult read(NacosConfigKey key, String revision, boolean longPolling) {
            synchronized (contentLock) {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                String currentRevision = String.valueOf(revisionCounter.get());
                if (longPolling && revision != null && revision.equals(currentRevision)) {
                    longPollStarted.countDown();
                    while (revision.equals(currentRevision)
                            && System.nanoTime() < deadline) {
                        try {
                            contentLock.wait(100);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("Interrupted while watching test configuration", e);
                        }
                        currentRevision = String.valueOf(revisionCounter.get());
                    }
                }
                return new ReadResult(
                        revision == null || !revision.equals(currentRevision),
                        currentRevision,
                        Optional.of(content));
            }
        }
    };
    private final ExecutorService listenerExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final ExecutorService grpcExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final RecordingRegistration registrations = new RecordingRegistration();
    private final NacosAuthProperties authProperties = new NacosAuthProperties();
    private NacosAuthService authService;
    private NacosGrpcServer server;
    private ManagedChannel channel;
    private int grpcPort;

    @BeforeEach
    void startServer() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        grpcPort = port;

        NacosGrpcProperties properties = new NacosGrpcProperties();
        properties.setPort(port);
        authProperties.setEnabled(false);
        authService = new NacosAuthService(authProperties);
        authService.afterPropertiesSet();
        NacosListenerService listenerService = new NacosListenerService(configStore, listenerExecutor);
        server = new NacosGrpcServer(
                properties,
                authService,
                configStore,
                listenerService,
                registrations,
                grpcExecutor,
                8080);
        server.start();
        channel = ManagedChannelBuilder.forAddress("127.0.0.1", port).usePlaintext().build();
    }

    @Test
    void rejectsUnauthorizedConfigRequestsWhenAuthenticationIsEnabled() throws Exception {
        authProperties.setEnabled(true);
        authProperties.setAcceptAnyCredentials(true);
        authProperties.setTokenSecret("grpc-test-shared-secret");
        authService.afterPropertiesSet();

        RequestGrpc.RequestFutureStub stub = RequestGrpc.newFutureStub(channel);
        ConfigQueryRequest unauthorized = ConfigQueryRequest.build(
                "application.yaml", "DEFAULT_GROUP", "public");
        Object unauthorizedResponse = GrpcUtils.parse(
                stub.request(GrpcUtils.convert(unauthorized)).get(5, TimeUnit.SECONDS));
        assertThat(unauthorizedResponse).isInstanceOf(ConfigQueryResponse.class);
        ConfigQueryResponse denied = (ConfigQueryResponse) unauthorizedResponse;
        assertThat(denied.isSuccess()).isFalse();
        assertThat(denied.getErrorCode()).isEqualTo(ConfigQueryResponse.NO_RIGHT);

        String token = authService.login("client", "secret").orElseThrow();
        ConfigQueryRequest authorized = ConfigQueryRequest.build(
                "application.yaml", "DEFAULT_GROUP", "public");
        authorized.putHeader("accessToken", token);
        Object authorizedResponse = GrpcUtils.parse(
                stub.request(GrpcUtils.convert(authorized)).get(5, TimeUnit.SECONDS));
        assertThat(authorizedResponse).isInstanceOf(ConfigQueryResponse.class);
        assertThat(((ConfigQueryResponse) authorizedResponse).isSuccess()).isTrue();
    }

    @AfterEach
    void stopServer() throws Exception {
        if (channel != null) {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
        if (server != null) {
            server.stop();
        }
        listenerExecutor.close();
        grpcExecutor.close();
    }

    @Test
    void answersNacosServerCheckAndConfigQuery() throws Exception {
        NacosConfigKey key = new NacosConfigKey("application.yaml", "DEFAULT_GROUP", "public");
        RequestGrpc.RequestFutureStub stub = RequestGrpc.newFutureStub(channel);
        Payload checkPayload = stub.request(GrpcUtils.convert(new com.alibaba.nacos.api.remote.request.ServerCheckRequest()))
                .get(5, TimeUnit.SECONDS);
        Object check = GrpcUtils.parse(checkPayload);
        assertThat(check).isInstanceOf(ServerCheckResponse.class);
        assertThat(((ServerCheckResponse) check).isSupportAbilityNegotiation()).isTrue();

        Payload queryPayload = stub.request(GrpcUtils.convert(
                ConfigQueryRequest.build(key.dataId(), key.group(), key.tenant())))
                .get(5, TimeUnit.SECONDS);
        Object parsed = GrpcUtils.parse(queryPayload);
        assertThat(parsed).isInstanceOf(ConfigQueryResponse.class);
        ConfigQueryResponse response = (ConfigQueryResponse) parsed;
        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getContent()).isEqualTo(content);
        assertThat(response.getMd5()).isEqualTo(configStore.md5(content));
    }

    @Test
    void realNacosConfigClientReadsThroughTheGrpcCompatibilityEndpoint() throws Exception {
        Properties properties = new Properties();
        properties.setProperty(PropertyKeyConst.SERVER_ADDR, "127.0.0.1:" + (grpcPort - 1000));
        properties.setProperty(PropertyKeyConst.CONFIG_REQUEST_TIMEOUT, "-1");

        ConfigService configService = ConfigFactory.createConfigService(properties);
        try {
            assertThat(configService.getConfig("application.yaml", "DEFAULT_GROUP", 5000))
                    .isEqualTo(content);
        } finally {
            configService.shutDown();
        }
    }

    @Test
    void realNacosConfigClientListenerReceivesActiveServerPush() throws Exception {
        Properties properties = new Properties();
        properties.setProperty(PropertyKeyConst.SERVER_ADDR, "127.0.0.1:" + (grpcPort - 1000));
        properties.setProperty(PropertyKeyConst.CONFIG_REQUEST_TIMEOUT, "-1");

        ConfigService configService = ConfigFactory.createConfigService(properties);
        try {
            assertThat(configService.getConfig("application.yaml", "DEFAULT_GROUP", 5000))
                    .isEqualTo(content);

            BlockingQueue<String> received = new ArrayBlockingQueue<>(4);
            configService.addListener("application.yaml", "DEFAULT_GROUP", new Listener() {
                @Override
                public java.util.concurrent.Executor getExecutor() {
                    return Runnable::run;
                }

                @Override
                public void receiveConfigInfo(String configInfo) {
                    received.add(configInfo);
                }
            });

            // ConfigBatchListen 必须立即返回并在后台安装一个
            // KIE 监听。任一步骤回归，这个锁存器就永远打不开了。
            assertThat(longPollStarted.await(3, TimeUnit.SECONDS)).isTrue();

            updateContent("feature.enabled: false\n");
            assertThat(received.poll(5, TimeUnit.SECONDS))
                    .isEqualTo("feature.enabled: false\n");
            assertThat(configService.getConfig("application.yaml", "DEFAULT_GROUP", 5000))
                    .isEqualTo("feature.enabled: false\n");
        } finally {
            configService.shutDown();
        }
    }

    @Test
    void connectionSetupRegistersClientAndCompletionUnregistersIt() throws Exception {
        OpenStream stream = openConnection("tenant-a");

        assertThat(stream.registration().tenant).isEqualTo("tenant-a");
        assertThat(stream.registration().labels).containsEntry("AppName", "orders");
        assertThat(stream.registration().remoteHost).isEqualTo("127.0.0.1");

        stream.request().onCompleted();

        assertThat(stream.registration().awaitClosed()).isTrue();
    }

    @Test
    void connectionErrorUnregistersClient() throws Exception {
        OpenStream stream = openConnection("");

        stream.request().onError(Status.CANCELLED.asRuntimeException());

        assertThat(stream.registration().awaitClosed()).isTrue();
    }

    private void updateContent(String nextContent) {
        synchronized (contentLock) {
            content = nextContent;
            revisionCounter.incrementAndGet();
            contentLock.notifyAll();
        }
    }

    private OpenStream openConnection(String tenant) throws Exception {
        CountDownLatch acknowledged = new CountDownLatch(1);
        StreamObserver<Payload> responseObserver = new StreamObserver<>() {
            @Override
            public void onNext(Payload payload) {
                Object response = GrpcUtils.parse(payload);
                if (response instanceof SetupAckRequest) {
                    acknowledged.countDown();
                }
            }

            @Override
            public void onError(Throwable throwable) {
            }

            @Override
            public void onCompleted() {
            }
        };

        StreamObserver<Payload> request = BiRequestStreamGrpc.newStub(channel)
                .requestBiStream(responseObserver);
        ConnectionSetupRequest setup = new ConnectionSetupRequest();
        setup.setTenant(tenant);
        setup.setLabels(Map.of("AppName", "orders", "clientVersion", "2.5.2"));
        request.onNext(GrpcUtils.convert(setup));

        assertThat(acknowledged.await(5, TimeUnit.SECONDS)).isTrue();
        RecordedRegistration registration = registrations.entries.poll(5, TimeUnit.SECONDS);
        assertThat(registration).isNotNull();
        assertThat(registration.remoteHost).isEqualTo("127.0.0.1");
        return new OpenStream(request, registration);
    }

    private record OpenStream(StreamObserver<Payload> request, RecordedRegistration registration) {
    }

    private static final class RecordingRegistration implements NacosClientRegistration {
        private final BlockingQueue<RecordedRegistration> entries = new ArrayBlockingQueue<>(8);

        @Override
        public Registration register(String tenant, Map<String, String> labels, String remoteHost) {
            RecordedRegistration registration =
                    new RecordedRegistration(tenant, labels, remoteHost);
            entries.add(registration);
            return registration::close;
        }
    }

    private static final class RecordedRegistration {
        private final String tenant;
        private final Map<String, String> labels;
        private final String remoteHost;
        private final CountDownLatch closed = new CountDownLatch(1);

        private RecordedRegistration(String tenant, Map<String, String> labels, String remoteHost) {
            this.tenant = tenant;
            this.labels = labels;
            this.remoteHost = remoteHost;
        }

        private void close() {
            closed.countDown();
        }

        private boolean awaitClosed() throws InterruptedException {
            return closed.await(5, TimeUnit.SECONDS);
        }
    }
}
