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

import com.rxda.nacoscseconfigbridge.auth.NacosAuthService;
import com.rxda.nacoscseconfigbridge.cse.KieConfigStore;
import com.rxda.nacoscseconfigbridge.config.NacosGrpcProperties;

/**
 * Nacos 2.x Config gRPC 兼容端点。
 *
 * <p>Nacos 客户端用 unary RPC 做查询和批量监听请求，双向流用于
 * 连接建立和服务端推送。该端点刻意不保存任何配置数据；每次查询都走
 * KIE，长轮询请求由 KIE 挂起直到版本变更。</p>
 */
@Component
public class NacosGrpcServer implements SmartLifecycle {

    static {
        // 通常由 RpcClient 初始化这个注册表。但代理是以服务端身份运行的，
        // 所以必须自己初始化 Nacos 请求/响应类型注册表。
        PayloadRegistry.init();
    }

    private final NacosGrpcProperties properties;
    private final NacosAuthService authService;
    private final KieConfigStore configStore;
    private final NacosListenerService listenerService;
    private final NacosClientRegistration clientRegistration;
    private final Executor grpcExecutor;
    private final int httpPort;

    private static final Context.Key<SocketAddress> REMOTE_ADDRESS = Context.key("nacos.remote-address");

    private volatile Server server;
    private final Map<SocketAddress, ClientConnection> connections = new ConcurrentHashMap<>();

    /**
     * 创建 Nacos gRPC 服务，并将其与 KIE 监听服务接线。
     *
     * @param properties gRPC 端口与消息大小设置
     * @param authService Nacos 登录令牌校验服务
     * @param configStore 精确配置存储
     * @param listenerService 长轮询监听协调器
     * @param clientRegistration 可选的服务中心注册边界
     * @param grpcExecutor gRPC 回调用线程池
     * @param httpPort 内嵌 HTTP 端口，用于推导默认 gRPC 端口
     */
    public NacosGrpcServer(
            NacosGrpcProperties properties,
            NacosAuthService authService,
            KieConfigStore configStore,
            NacosListenerService listenerService,
            NacosClientRegistration clientRegistration,
            @Qualifier("nacosGrpcExecutor") Executor grpcExecutor,
            @Value("${server.port:8080}") int httpPort) {
        this.properties = properties;
        this.authService = authService;
        this.configStore = configStore;
        this.listenerService = listenerService;
        this.clientRegistration = clientRegistration;
        this.grpcExecutor = grpcExecutor;
        this.httpPort = httpPort;
    }

    /** 兼容端点启用时启动 gRPC 监听。 */
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

    /** 停止 gRPC 监听并释放所有客户端监听。 */
    @Override
    public synchronized void stop() {
        if (server != null) {
            server.shutdown();
            server = null;
        }
        connections.values().forEach(ClientConnection::close);
        connections.clear();
    }

    /** 停止服务并调用 Spring 的生命周期回调。 */
    @Override
    public void stop(Runnable callback) {
        stop();
        callback.run();
    }

    /** 返回底层 gRPC 服务是否正在接受请求。 */
    @Override
    public boolean isRunning() {
        Server current = server;
        return current != null && !current.isShutdown();
    }

    /** 返回 Spring 是否应自动启动该生命周期。 */
    @Override
    public boolean isAutoStartup() {
        return true;
    }

    /** 把关闭阶段排在普通应用组件之后。 */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }
    /** 把解码后的 Nacos 请求路由到相应的协议处理器。 */

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
            if (!isAuthorized(request)) {
                return ConfigQueryResponse.buildFailResponse(
                        ConfigQueryResponse.NO_RIGHT, "invalid or missing accessToken");
            }
            return query(query);
        }
        if (request instanceof ConfigBatchListenRequest listen) {
            if (!isAuthorized(request)) {
                return ConfigChangeBatchListenResponse.buildFailResponse(
                        "invalid or missing accessToken");
            }
            return listen(listen, remoteAddress);
        }
        if (request instanceof ConfigPublishRequest) {
            if (!isAuthorized(request)) {
                return ConfigPublishResponse.buildFailResponse(
                        ConfigQueryResponse.NO_RIGHT, "invalid or missing accessToken");
            }
            return ConfigPublishResponse.buildFailResponse(405, "This proxy is read-only; publish in CSE");
        }
        if (request instanceof ConfigRemoveRequest) {
            if (!isAuthorized(request)) {
                return ConfigRemoveResponse.buildFailResponse("invalid or missing accessToken");
            }
            return ConfigRemoveResponse.buildFailResponse("This proxy is read-only; remove in CSE");
        }
        return ErrorResponse.build(NacosException.SERVER_ERROR,
                "Unsupported Nacos Config request: " + request.getClass().getSimpleName());
    }

    /** 校验业务 Nacos 请求携带的登录令牌。 */
    private boolean isAuthorized(Request request) {
        String token = request.getHeader("accessToken");
        if (token == null || token.isBlank()) {
            String authorization = request.getHeader("Authorization");
            if (authorization != null && authorization.startsWith("Bearer ")) {
                token = authorization.substring("Bearer ".length());
            }
        }
        return authService.isTokenValid(token);
    }
    /** 读取请求的配置或元数据。 */

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
            response.setContentType(NacosConfigFormat.fromNacosType(result.valueType(), key.dataId()));
            return response;
        } catch (RuntimeException e) {
            return ConfigQueryResponse.buildFailResponse(NacosException.SERVER_ERROR,
                    "Unable to read CSE KIE configuration: " + safeMessage(e));
        }
    }
    /** 列出请求的资源。 */

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
    /** 为协议错误返回安全的诊断信息。 */

    private String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }
    /** 分发请求并把响应序列化为 gRPC payload。 */

    private Payload responsePayload(Request request, SocketAddress remoteAddress) {
        Response response = dispatch(request, remoteAddress);
        response.setRequestId(request.getRequestId());
        return GrpcUtils.convert(response);
    }
    /** 解码 gRPC payload，并校验其中包含的是 Nacos 请求。 */

    private Request parse(Payload payload) {
        Object parsed = GrpcUtils.parse(payload);
        if (!(parsed instanceof Request request)) {
            throw new IllegalArgumentException("Nacos gRPC payload is not a request");
        }
        return request;
    }

    /** 处理 unary Nacos 健康检查、查询和监听请求。 */
    private final class UnaryRequestService extends RequestGrpc.RequestImplBase {
        /** 处理一个 unary Nacos RPC 并返回其响应 payload。 */
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

    /** 处理连接建立和服务端推送通知。 */
    private final class BidirectionalRequestService extends BiRequestStreamGrpc.BiRequestStreamImplBase {
        /** 为连接建立和服务端推送通知打开双向流。 */
        @Override
        public StreamObserver<Payload> requestBiStream(StreamObserver<Payload> observer) {
            SocketAddress remoteAddress = REMOTE_ADDRESS.get();
            ClientConnection connection = new ClientConnection(remoteAddress, observer);
            return new StreamObserver<>() {
                /** 处理双向流上收到的一个请求。 */
                @Override
                public void onNext(Payload payload) {
                    try {
                        Request request = parse(payload);
                        if (request instanceof ConnectionSetupRequest) {
                            // 当服务端声明支持能力协商时，
                            // Nacos 会等待这个确认回包。
                            observer.onNext(GrpcUtils.convert(new SetupAckRequest(Map.of())));
                            connection.attach((ConnectionSetupRequest) request);
                        }
                    } catch (RuntimeException e) {
                        connection.close();
                        observer.onError(Status.INTERNAL.withDescription("Nacos stream request failed")
                                .withCause(e).asRuntimeException());
                    }
                }

                /** 流出错后关闭该连接。 */
                @Override
                public void onError(Throwable throwable) {
                    connection.close();
                }

                /** 客户端完成流之后关闭该连接。 */
                @Override
                public void onCompleted() {
                    connection.close();
                    observer.onCompleted();
                }
            };
        }
    }

    /** 跟踪一个 Nacos gRPC 连接的监听和注册状态。 */
    private final class ClientConnection {
        private final SocketAddress remoteAddress;
        private final StreamObserver<Payload> observer;
        private final Map<NacosConfigKey, NacosListenerService.Watch> watches = new ConcurrentHashMap<>();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicReference<NacosClientRegistration.Registration> registration = new AtomicReference<>();
        private volatile boolean attached;
        /** 创建按连接的监听与注册状态。 */

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
        /** 注册客户端并捕获其连接元数据。 */

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
        /** 按客户端最新一批监听请求对齐活跃监听。 */

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
        /** 向客户端推送配置变更通知。 */

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
        /** 关闭该资源并释放关联状态。 */

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
    /** 从传输层地址提取可打印的主机。 */

    private static String remoteHost(SocketAddress address) {
        if (address instanceof InetSocketAddress inetAddress) {
            return inetAddress.getHostString();
        }
        return address == null ? "" : address.toString();
    }

    /** 把传输层对端地址复制到 gRPC 请求上下文。 */
    private static final class RemoteAddressInterceptor implements ServerInterceptor {
        /** 保存传输层对端地址，供下游请求处理器使用。 */
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
