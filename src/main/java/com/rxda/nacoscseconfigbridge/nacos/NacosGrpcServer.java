package com.rxda.nacoscseconfigbridge.nacos;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import com.alibaba.nacos.api.config.remote.request.ConfigBatchListenRequest;
import com.alibaba.nacos.api.config.remote.request.ConfigChangeNotifyRequest;
import com.alibaba.nacos.api.config.remote.request.ConfigQueryRequest;
import com.alibaba.nacos.api.config.remote.request.ConfigPublishRequest;
import com.alibaba.nacos.api.config.remote.request.ConfigRemoveRequest;
import com.alibaba.nacos.api.config.remote.response.ConfigChangeBatchListenResponse;
import com.alibaba.nacos.api.config.remote.response.ConfigPublishResponse;
import com.alibaba.nacos.api.config.remote.response.ConfigQueryResponse;
import com.alibaba.nacos.api.config.remote.response.ConfigRemoveResponse;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.grpc.auto.BiRequestStreamGrpc;
import com.alibaba.nacos.api.grpc.auto.Payload;
import com.alibaba.nacos.api.grpc.auto.RequestGrpc;
import com.alibaba.nacos.api.remote.request.ClientDetectionRequest;
import com.alibaba.nacos.api.remote.request.ConnectionSetupRequest;
import com.alibaba.nacos.api.remote.request.HealthCheckRequest;
import com.alibaba.nacos.api.remote.request.Request;
import com.alibaba.nacos.api.remote.request.SetupAckRequest;
import com.alibaba.nacos.api.remote.request.ServerCheckRequest;
import com.alibaba.nacos.api.remote.response.ClientDetectionResponse;
import com.alibaba.nacos.api.remote.response.ErrorResponse;
import com.alibaba.nacos.api.remote.response.HealthCheckResponse;
import com.alibaba.nacos.api.remote.response.Response;
import com.alibaba.nacos.api.remote.response.ServerCheckResponse;
import com.alibaba.nacos.common.remote.client.grpc.GrpcUtils;
import com.alibaba.nacos.common.remote.PayloadRegistry;
import com.alibaba.nacos.shaded.io.grpc.Server;
import com.alibaba.nacos.shaded.io.grpc.ServerBuilder;
import com.alibaba.nacos.shaded.io.grpc.ServerCall;
import com.alibaba.nacos.shaded.io.grpc.ServerCallHandler;
import com.alibaba.nacos.shaded.io.grpc.ServerInterceptor;
import com.alibaba.nacos.shaded.io.grpc.Status;
import com.alibaba.nacos.shaded.io.grpc.Context;
import com.alibaba.nacos.shaded.io.grpc.Contexts;
import com.alibaba.nacos.shaded.io.grpc.Grpc;
import com.alibaba.nacos.shaded.io.grpc.Metadata;
import com.alibaba.nacos.shaded.io.grpc.stub.StreamObserver;

import com.rxda.nacoscseconfigbridge.cse.KieConfigStore;
import com.rxda.nacoscseconfigbridge.config.NacosGrpcProperties;

/**
 * Nacos 2.x Config gRPC compatibility endpoint.
 *
 * <p>Nacos clients use a unary RPC for queries and batch listen requests. The
 * bidirectional stream is used for connection setup and server push requests.
 * This endpoint deliberately keeps no configuration data; every query goes to
 * KIE and a long-poll request is held by KIE until its revision changes.</p>
 */
@Component
public class NacosGrpcServer implements SmartLifecycle {

    static {
        // RpcClient normally initializes this registry. The proxy is a server,
        // so it must initialize the Nacos request/response type registry itself.
        PayloadRegistry.init();
    }

    private final NacosGrpcProperties properties;
    private final KieConfigStore configStore;
    private final NacosListenerService listenerService;
    private final NacosClientRegistration clientRegistration;
    private final Executor grpcExecutor;
    private final int httpPort;

    private static final Context.Key<SocketAddress> REMOTE_ADDRESS = Context.key("nacos.remote-address");

    private volatile Server server;
    private final Map<SocketAddress, ClientConnection> connections = new ConcurrentHashMap<>();

    public NacosGrpcServer(
            NacosGrpcProperties properties,
            KieConfigStore configStore,
            NacosListenerService listenerService,
            NacosClientRegistration clientRegistration,
            @Qualifier("nacosGrpcExecutor") Executor grpcExecutor,
            @Value("${server.port:8080}") int httpPort) {
        this.properties = properties;
        this.configStore = configStore;
        this.listenerService = listenerService;
        this.clientRegistration = clientRegistration;
        this.grpcExecutor = grpcExecutor;
        this.httpPort = httpPort;
    }

    @Override
    public synchronized void start() {
        if (!properties.isEnabled() || server != null) {
            return;
        }
        int port = properties.getPort() > 0 ? properties.getPort() : httpPort + 1000;
        try {
            server = ServerBuilder.forPort(port)
                    .executor(grpcExecutor)
                    .maxInboundMessageSize(properties.getMaxInboundMessageSize())
                    .intercept(new RemoteAddressInterceptor())
                    .addService(new UnaryRequestService())
                    .addService(new BidirectionalRequestService())
                    .build()
                    .start();
        } catch (IOException e) {
            throw new IllegalStateException("Unable to start Nacos Config gRPC server on port " + port, e);
        }
    }

    @Override
    public synchronized void stop() {
        if (server != null) {
            server.shutdown();
            server = null;
        }
        connections.values().forEach(ClientConnection::close);
        connections.clear();
    }

    @Override
    public void stop(Runnable callback) {
        stop();
        callback.run();
    }

    @Override
    public boolean isRunning() {
        Server current = server;
        return current != null && !current.isShutdown();
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    private Response dispatch(Request request, SocketAddress remoteAddress) {
        if (request instanceof ServerCheckRequest) {
            return new ServerCheckResponse(UUID.randomUUID().toString(), true);
        }
        if (request instanceof HealthCheckRequest) {
            return new HealthCheckResponse();
        }
        if (request instanceof ClientDetectionRequest) {
            return new ClientDetectionResponse();
        }
        if (request instanceof ConfigQueryRequest query) {
            return query(query);
        }
        if (request instanceof ConfigBatchListenRequest listen) {
            return listen(listen, remoteAddress);
        }
        if (request instanceof ConfigPublishRequest) {
            return ConfigPublishResponse.buildFailResponse(405, "This proxy is read-only; publish in CSE");
        }
        if (request instanceof ConfigRemoveRequest) {
            return ConfigRemoveResponse.buildFailResponse("This proxy is read-only; remove in CSE");
        }
        return ErrorResponse.build(NacosException.SERVER_ERROR,
                "Unsupported Nacos Config request: " + request.getClass().getSimpleName());
    }

    private ConfigQueryResponse query(ConfigQueryRequest request) {
        NacosConfigKey key = new NacosConfigKey(request.getDataId(), request.getGroup(), request.getTenant());
        try {
            KieConfigStore.ReadResult result = configStore.read(key, null, false);
            if (result.content().isEmpty()) {
                return ConfigQueryResponse.buildFailResponse(ConfigQueryResponse.CONFIG_NOT_FOUND,
                        "config not found");
            }
            ConfigQueryResponse response = ConfigQueryResponse.buildSuccessResponse(result.content().get());
            response.setMd5(configStore.md5(result.content().get()));
            response.setContentType("text");
            return response;
        } catch (RuntimeException e) {
            return ConfigQueryResponse.buildFailResponse(NacosException.SERVER_ERROR,
                    "Unable to read CSE KIE configuration: " + safeMessage(e));
        }
    }

    private ConfigChangeBatchListenResponse listen(ConfigBatchListenRequest request, SocketAddress remoteAddress) {
        List<NacosListenerEntry> entries = request.getConfigListenContexts().stream()
                .map(context -> new NacosListenerEntry(
                        new NacosConfigKey(context.getDataId(), context.getGroup(), context.getTenant()),
                        context.getMd5()))
                .toList();
        if (!request.isListen()) {
            ClientConnection connection = remoteAddress == null ? null : connections.get(remoteAddress);
            if (connection != null) {
                connection.replaceWatches(entries, List.of());
            }
            return new ConfigChangeBatchListenResponse();
        }
        try {
            ConfigChangeBatchListenResponse response = new ConfigChangeBatchListenResponse();
            List<NacosConfigKey> changed = listenerService.checkNow(entries);
            for (NacosConfigKey key : changed) {
                response.addChangeConfig(key.dataId(), key.group(), key.tenant());
            }
            ClientConnection connection = remoteAddress == null ? null : connections.get(remoteAddress);
            if (connection != null) {
                connection.replaceWatches(entries, changed);
            }
            return response;
        } catch (RuntimeException e) {
            return ConfigChangeBatchListenResponse.buildFailResponse(
                    "Unable to read CSE KIE configuration: " + safeMessage(e));
        }
    }

    private String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    private Payload responsePayload(Request request, SocketAddress remoteAddress) {
        Response response = dispatch(request, remoteAddress);
        response.setRequestId(request.getRequestId());
        return GrpcUtils.convert(response);
    }

    private Request parse(Payload payload) {
        Object parsed = GrpcUtils.parse(payload);
        if (!(parsed instanceof Request request)) {
            throw new IllegalArgumentException("Nacos gRPC payload is not a request");
        }
        return request;
    }

    private final class UnaryRequestService extends RequestGrpc.RequestImplBase {
        @Override
        public void request(Payload payload, StreamObserver<Payload> observer) {
            try {
                observer.onNext(responsePayload(parse(payload), REMOTE_ADDRESS.get()));
                observer.onCompleted();
            } catch (RuntimeException e) {
                observer.onError(Status.INTERNAL.withDescription("Nacos request failed")
                        .withCause(e).asRuntimeException());
            }
        }
    }

    private final class BidirectionalRequestService extends BiRequestStreamGrpc.BiRequestStreamImplBase {
        @Override
        public StreamObserver<Payload> requestBiStream(StreamObserver<Payload> observer) {
            SocketAddress remoteAddress = REMOTE_ADDRESS.get();
            ClientConnection connection = new ClientConnection(remoteAddress, observer);
            return new StreamObserver<>() {
                @Override
                public void onNext(Payload payload) {
                    try {
                        Request request = parse(payload);
                        if (request instanceof ConnectionSetupRequest) {
                            // Nacos waits for this acknowledgement when the server
                            // advertises capability negotiation support.
                            observer.onNext(GrpcUtils.convert(new SetupAckRequest(Map.of())));
                            connection.attach((ConnectionSetupRequest) request);
                        }
                    } catch (RuntimeException e) {
                        connection.close();
                        observer.onError(Status.INTERNAL.withDescription("Nacos stream request failed")
                                .withCause(e).asRuntimeException());
                    }
                }

                @Override
                public void onError(Throwable throwable) {
                    connection.close();
                }

                @Override
                public void onCompleted() {
                    connection.close();
                    observer.onCompleted();
                }
            };
        }
    }

    private final class ClientConnection {
        private final SocketAddress remoteAddress;
        private final StreamObserver<Payload> observer;
        private final Map<NacosConfigKey, NacosListenerService.Watch> watches = new ConcurrentHashMap<>();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicReference<NacosClientRegistration.Registration> registration = new AtomicReference<>();
        private volatile boolean attached;

        private ClientConnection(SocketAddress remoteAddress, StreamObserver<Payload> observer) {
            this.remoteAddress = remoteAddress;
            this.observer = observer;
            if (remoteAddress != null) {
                ClientConnection previous = connections.put(remoteAddress, this);
                if (previous != null && previous != this) {
                    previous.close();
                }
            }
        }

        private void attach(ConnectionSetupRequest request) {
            if (closed.get() || attached) {
                return;
            }
            attached = true;
            NacosClientRegistration.Registration created = clientRegistration.register(
                    request.getTenant(), request.getLabels(), remoteHost(remoteAddress));
            if (registration.compareAndSet(null, created) && closed.get()
                    && registration.compareAndSet(created, null)) {
                created.close();
            }
        }

        private void replaceWatches(List<NacosListenerEntry> entries, List<NacosConfigKey> immediatelyChanged) {
            if (closed.get()) {
                return;
            }
            Map<NacosConfigKey, NacosListenerEntry> wanted = new java.util.HashMap<>();
            for (NacosListenerEntry entry : entries) {
                if (!immediatelyChanged.contains(entry.key())) {
                    wanted.put(entry.key(), entry);
                }
            }
            for (Map.Entry<NacosConfigKey, NacosListenerService.Watch> existing : watches.entrySet()) {
                if (!wanted.containsKey(existing.getKey())
                        && watches.remove(existing.getKey(), existing.getValue())) {
                    existing.getValue().close();
                }
            }
            wanted.forEach((key, entry) -> {
                NacosListenerService.Watch old = watches.put(key, listenerService.startWatch(entry, this::push));
                if (old != null) {
                    old.close();
                }
            });
        }

        private void push(NacosConfigKey key) {
            if (closed.get()) {
                return;
            }
            ConfigChangeNotifyRequest request = ConfigChangeNotifyRequest.build(
                    key.dataId(), key.group(), key.tenant());
            request.setRequestId(UUID.randomUUID().toString());
            try {
                synchronized (observer) {
                    if (!closed.get()) {
                        observer.onNext(GrpcUtils.convert(request));
                    }
                }
            } catch (RuntimeException e) {
                close();
            }
        }

        private void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            if (remoteAddress != null) {
                connections.remove(remoteAddress, this);
            }
            watches.values().forEach(NacosListenerService.Watch::close);
            watches.clear();
            NacosClientRegistration.Registration current = registration.getAndSet(null);
            if (current != null) {
                current.close();
            }
        }
    }

    private static String remoteHost(SocketAddress address) {
        if (address instanceof InetSocketAddress inetAddress) {
            return inetAddress.getHostString();
        }
        return address == null ? "" : address.toString();
    }

    private static final class RemoteAddressInterceptor implements ServerInterceptor {
        @Override
        public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                ServerCall<ReqT, RespT> call,
                Metadata headers,
                ServerCallHandler<ReqT, RespT> next) {
            SocketAddress remoteAddress = call.getAttributes().get(Grpc.TRANSPORT_ATTR_REMOTE_ADDR);
            return Contexts.interceptCall(
                    Context.current().withValue(REMOTE_ADDRESS, remoteAddress), call, headers, next);
        }
    }
}
