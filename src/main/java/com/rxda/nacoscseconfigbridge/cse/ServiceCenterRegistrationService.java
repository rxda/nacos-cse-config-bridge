package com.rxda.nacoscseconfigbridge.cse;

import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.apache.servicecomb.foundation.auth.AuthHeaderProvider;
import org.apache.servicecomb.http.client.auth.RequestAuthHeaderProvider;
import org.apache.servicecomb.http.client.common.HttpConfiguration;
import org.apache.servicecomb.service.center.client.ServiceCenterAddressManager;
import org.apache.servicecomb.service.center.client.ServiceCenterClient;
import org.apache.servicecomb.service.center.client.model.Framework;
import org.apache.servicecomb.service.center.client.model.Microservice;
import org.apache.servicecomb.service.center.client.model.MicroserviceInstance;
import org.apache.servicecomb.service.center.client.model.MicroserviceInstanceStatus;
import org.apache.servicecomb.service.center.client.model.MicroserviceStatus;
import org.apache.servicecomb.service.center.client.model.RegisteredMicroserviceInstanceResponse;
import org.apache.servicecomb.service.center.client.model.RegisteredMicroserviceResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.google.common.eventbus.EventBus;
import com.rxda.nacoscseconfigbridge.config.ServiceCenterProperties;
import com.rxda.nacoscseconfigbridge.nacos.NacosClientRegistration;

/**
 * 观察 Nacos Config gRPC 客户端，并将其镜像到 CSE 服务中心。
 *
 * <p>Config 客户端不是服务发现客户端。因此该类仅为展示创建微服务，
 * 只有当客户端明确提供业务端点标签时才创建实例。绝不会把 Nacos gRPC
 * 源端口当作应用端口使用。</p>
 */
@Service
public class ServiceCenterRegistrationService implements NacosClientRegistration {

    private static final Logger LOGGER = LoggerFactory.getLogger(ServiceCenterRegistrationService.class);
    private static final String NACOS_SOURCE = "SDK";
    private static final String DEFAULT_PROTOCOL = "http";
    private static final String DEFAULT_CSE_ENVIRONMENT = "development";
    private static final Set<String> CSE_ENVIRONMENTS =
            Set.of("development", "testing", "acceptance", "production");

    private final ServiceCenterProperties properties;
    private final ScheduledExecutorService scheduler;
    private final ServiceCenterClient client;
    private final Map<String, ActiveRegistration> activeInstances = new HashMap<>();
    private final Object registrationLock = new Object();

    /**
     * 创建带可选服务中心注册的观察者。
     *
     * @param properties 注册设置
     * @param authHeaderProviders 服务中心客户端使用的认证提供者
     * @param scheduler 实例心跳调度器
     */
    @Autowired
    public ServiceCenterRegistrationService(
            ServiceCenterProperties properties,
            List<AuthHeaderProvider> authHeaderProviders,
            @Qualifier("nacosServiceCenterExecutor") ScheduledExecutorService scheduler) {
        this.properties = properties;
        this.scheduler = scheduler;
        // 启用了开关但没配地址时，不应阻止 Nacos 兼容端点启动。
        // 这样部署时只需设置 CSE_SERVICE_CENTER_ADDR 即可启用注册。
        boolean configured = properties.isEnabled() && StringUtils.hasText(properties.getServerAddr());
        this.client = configured ? createClient(properties, authHeaderProviders) : null;
        if (configured) {
            LOGGER.info("CSE Service Center observation registration is enabled: {}", properties.getServerAddr());
        } else if (properties.isEnabled()) {
            LOGGER.warn("CSE Service Center registration is enabled but no server address was configured");
        }
    }

    /**
     * 创建使用注入客户端的注册服务，主要用于测试。
     *
     * @param properties 注册设置
     * @param client 服务中心客户端，禁用注册时为 {@code null}
     * @param scheduler 实例心跳调度器
     */
    ServiceCenterRegistrationService(
            ServiceCenterProperties properties,
            ServiceCenterClient client,
            ScheduledExecutorService scheduler) {
        this.properties = properties;
        this.client = client;
        this.scheduler = scheduler;
    }

    /**
     * 将一个 Nacos gRPC 连接镜像到服务中心。
     *
     * @param tenant 连接关联的 Nacos 租户
     * @param labels Nacos 连接标签
     * @param remoteHost Nacos 客户端的对端地址
     * @return 注册句柄；关闭它会释放实例引用
     */
    @Override
    public Registration register(String tenant, Map<String, String> labels, String remoteHost) {
        if (!properties.isEnabled() || client == null) {
            return () -> {
            };
        }

        Map<String, String> safeLabels = labels == null ? Map.of() : Map.copyOf(labels);
        String serviceName = value(safeLabels, properties.getServiceNameLabel());
        if (!StringUtils.hasText(serviceName)) {
            serviceName = properties.getDefaultServiceName();
        }
        if (!StringUtils.hasText(serviceName)) {
            LOGGER.warn("Ignoring Nacos client without appName label; configure "
                    + "srv-nacos-cse-config-bridge.service-center.default-service-name to opt into a fallback");
            return () -> {
            };
        }

        try {
            String serviceId = ensureMicroservice(serviceName, tenant, safeLabels);
            Endpoint endpoint = properties.isInstanceEnabled()
                    ? endpoint(safeLabels, remoteHost)
                    : null;
            if (endpoint == null) {
                LOGGER.info("Registered CSE microservice {} without an instance endpoint; "
                        + "the Nacos Config client did not provide a business port", serviceName);
                return () -> {
                };
            }
            return registerInstance(serviceId, serviceName, endpoint, safeLabels);
        } catch (RuntimeException e) {
            // 服务中心不能拖垮 Nacos Config 兼容链路。
            LOGGER.warn("Unable to mirror Nacos client {} into CSE Service Center", serviceName, e);
            return () -> {
            };
        }
    }
    /**
     * 为 Nacos 客户端查找或创建展示用微服务。
     *
     * @param serviceName 客户端上报的服务名
     * @param tenant 客户端关联的 Nacos 租户
     * @param labels 客户端连接标签
     * @return 服务中心微服务标识
     */
    private String ensureMicroservice(String serviceName, String tenant, Map<String, String> labels) {
        Microservice service = new Microservice();
        service.setAppId(properties.getAppId());
        service.setServiceName(serviceName);
        service.setVersion(properties.getVersion());
        service.setEnvironment(environment(tenant));
        service.setRegisterBy(NACOS_SOURCE);
        service.setStatus(MicroserviceStatus.UP);

        Framework framework = new Framework();
        framework.setName("NacosConfig");
        framework.setVersion(value(labels, "clientVersion", "2.x"));
        service.setFramework(framework);

        Map<String, String> serviceProperties = new HashMap<>();
        serviceProperties.put("registration.source", NACOS_SOURCE);
        serviceProperties.put("nacos.tenant", tenant == null ? "" : tenant);
        service.setProperties(serviceProperties);

        RegisteredMicroserviceResponse existing = client.queryServiceId(service);
        if (existing != null && StringUtils.hasText(existing.getServiceId())) {
            return existing.getServiceId();
        }
        RegisteredMicroserviceResponse created = client.registerMicroservice(service);
        if (created == null || !StringUtils.hasText(created.getServiceId())) {
            // 并发的代理/客户端可能在查询和创建之间已经创建了它。
            RegisteredMicroserviceResponse retry = client.queryServiceId(service);
            if (retry == null || !StringUtils.hasText(retry.getServiceId())) {
                throw new IllegalStateException("CSE did not return a microservice id");
            }
            return retry.getServiceId();
        }
        return created.getServiceId();
    }
    /**
     * 注册业务端点，并为其安排服务中心心跳。
     *
     * @param serviceId 服务中心微服务标识
     * @param serviceName 日志中使用的展示名
     * @param endpoint 校验通过的业务端点
     * @param labels 复制到实例元数据的客户端标签
     * @return 引用计数的注册句柄
     */
    private Registration registerInstance(
            String serviceId, String serviceName, Endpoint endpoint, Map<String, String> labels) {
        String key = serviceId + "|" + endpoint.value();
        synchronized (registrationLock) {
            ActiveRegistration active = activeInstances.get(key);
            if (active != null) {
                return active.acquire();
            }

            MicroserviceInstance instance = new MicroserviceInstance();
            instance.setServiceId(serviceId);
            instance.setVersion(properties.getVersion());
            instance.setHostName(endpoint.host());
            instance.setEndpoints(List.of(endpoint.value()));
            instance.setStatus(MicroserviceInstanceStatus.UP);
            instance.setProperties(Map.of(
                    "registration.source", NACOS_SOURCE,
                    "nacos.client", value(labels, "clientVersion", "unknown")));

            RegisteredMicroserviceInstanceResponse registered = client.registerMicroserviceInstance(instance);
            if (registered == null || !StringUtils.hasText(registered.getInstanceId())) {
                throw new IllegalStateException("CSE did not return an instance id for " + serviceName);
            }
            String instanceId = registered.getInstanceId();
            long heartbeatSeconds = Math.max(5, properties.getHeartbeatIntervalSeconds());
            ScheduledFuture<?> heartbeat = scheduler.scheduleAtFixedRate(
                    () -> sendHeartbeat(serviceName, serviceId, instanceId),
                    heartbeatSeconds,
                    heartbeatSeconds,
                    TimeUnit.SECONDS);
            active = new ActiveRegistration(key, serviceId, instanceId, heartbeat);
            activeInstances.put(key, active);
            LOGGER.info("Registered CSE instance {} for {} at {}", instanceId, serviceName, endpoint.value());
            return active;
        }
    }
    /**
     * 发送一次心跳，并将失败隔离在协议端点之外。
     *
     * @param serviceName 日志中使用的服务名
     * @param serviceId 服务中心微服务标识
     * @param instanceId 服务中心实例标识
     */
    private void sendHeartbeat(String serviceName, String serviceId, String instanceId) {
        try {
            if (!client.sendHeartBeat(serviceId, instanceId)) {
                LOGGER.warn("CSE heartbeat rejected for {} instance {}", serviceName, instanceId);
            }
        } catch (RuntimeException e) {
            LOGGER.warn("CSE heartbeat failed for {} instance {}", serviceName, instanceId, e);
        }
    }
    /**
     * 从客户端标签和配置的回退值解析业务端点。
     *
     * @param labels 客户端连接标签
     * @param remoteHost 用作主机回退的 gRPC 对端地址
     * @return 校验通过的端点，没有可用端口时返回 {@code null}
     */
    private Endpoint endpoint(Map<String, String> labels, String remoteHost) {
        String explicit = value(labels, properties.getEndpointLabel());
        if (StringUtils.hasText(explicit)) {
            return parseExplicitEndpoint(explicit);
        }

        String portText = value(labels, properties.getPortLabel());
        if (!StringUtils.hasText(portText) && properties.getInstancePort() > 0) {
            portText = String.valueOf(properties.getInstancePort());
        }
        String host = value(labels, properties.getHostLabel(),
                StringUtils.hasText(properties.getInstanceHost()) ? properties.getInstanceHost() : remoteHost);
        if (!StringUtils.hasText(portText) || !StringUtils.hasText(host)) {
            return null;
        }
        try {
            int port = Integer.parseInt(portText);
            if (port < 1 || port > 65535) {
                return null;
            }
            String protocol = value(labels, properties.getProtocolLabel(), DEFAULT_PROTOCOL);
            URI uri = URI.create(protocol + "://" + hostForUri(host) + ":" + port);
            String uriHost = uri.getHost();
            if (!StringUtils.hasText(uriHost) || uri.getPort() != port) {
                return null;
            }
            return new Endpoint(unbracketHost(uriHost), uri.toString());
        } catch (IllegalArgumentException e) {
            LOGGER.warn("Ignoring invalid CSE business endpoint labels for host {} and port {}", host, portText);
            return null;
        }
    }
    /**
     * 解析完整的端点标签，没有 scheme 时补上 HTTP。
     *
     * @param explicit 端点标签
     * @return 校验通过的端点，标签非法时返回 {@code null}
     */
    private Endpoint parseExplicitEndpoint(String explicit) {
        try {
            URI uri = URI.create(explicit.contains("://") ? explicit : DEFAULT_PROTOCOL + "://" + explicit);
            if (!StringUtils.hasText(uri.getHost()) || uri.getPort() < 1 || uri.getPort() > 65535) {
                return null;
            }
            return new Endpoint(uri.getHost(), uri.toString());
        } catch (IllegalArgumentException e) {
            LOGGER.warn("Ignoring invalid CSE endpoint label {}", explicit);
            return null;
        }
    }
    /**
     * 将 Nacos 租户映射为 CSE 环境名之一。
     *
     * @param tenant Nacos 租户值
     * @return 合法的 CSE 环境名
     */
    private String environment(String tenant) {
        if (StringUtils.hasText(tenant)) {
            String candidate = tenant.trim();
            if (CSE_ENVIRONMENTS.contains(candidate)) {
                return candidate;
            }
        }

        String fallback = properties.getEnvironment() == null ? "" : properties.getEnvironment().trim();
        return CSE_ENVIRONMENTS.contains(fallback) ? fallback : DEFAULT_CSE_ENVIRONMENT;
    }
    /**
     * 在把 IPv6 主机拼入 URI 之前加上方括号。
     *
     * @param host 主机名或地址
     * @return URI 安全的主机表示
     */
    private static String hostForUri(String host) {
        return host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
    }
    /**
     * 去掉 {@link URI} 返回的 IPv6 主机两端的方括号。
     *
     * @param host URI 主机
     * @return 去掉方括号的主机
     */
    private static String unbracketHost(String host) {
        return host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
    }

    /**
     * 读取配置的连接标签。Nacos 2.5.2 会给 {@code nacos.app.conn.labels}
     * 中的每个键加上 {@code app_} 前缀，因此两种形式都接受。
     */
    private static String value(Map<String, String> labels, String key) {
        if (!StringUtils.hasText(key)) {
            return null;
        }
        // Nacos 2.x 会同时包含默认的原始标签（例如 AppName=unknown）
        // 和配置的 app_* 标签。只要带前缀的值存在，就优先使用它。
        String prefixed = labels.get("app_" + key);
        if (StringUtils.hasText(prefixed)) {
            return prefixed;
        }
        return labels.get(key);
    }
    /**
     * 读取标签，标签为空时返回回退值。
     *
     * @param labels 连接标签
     * @param key 逻辑标签名
     * @param fallback 回退值
     * @return 标签值或回退值
     */
    private static String value(Map<String, String> labels, String key, String fallback) {
        String value = value(labels, key);
        return StringUtils.hasText(value) ? value : fallback;
    }
    /**
     * 创建用于注册和心跳的服务中心客户端。
     *
     * @param properties 注册设置
     * @param authHeaderProviders 认证提供者
     * @return 配置好的服务中心客户端
     */
    private ServiceCenterClient createClient(
            ServiceCenterProperties properties, List<AuthHeaderProvider> authHeaderProviders) {
        if (!StringUtils.hasText(properties.getServerAddr())) {
            throw new IllegalStateException(
                    "srv-nacos-cse-config-bridge.service-center.server-addr must be configured when registration is enabled");
        }
        List<String> addresses = Arrays.stream(properties.getServerAddr().split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toList();
        EventBus eventBus = new EventBus("nacos-cse-service-center");
        ServiceCenterAddressManager addressManager = new ServiceCenterAddressManager(
                properties.getProject(), addresses, eventBus);
        RequestAuthHeaderProvider authProvider = signRequest -> {
            Map<String, String> headers = new HashMap<>();
            authHeaderProviders.forEach(provider -> headers.putAll(provider.authHeaders()));
            return headers;
        };
        HttpConfiguration.SSLProperties ssl = new HttpConfiguration.SSLProperties();
        ssl.setEnabled(properties.isSslEnabled());
        return new ServiceCenterClient(
                addressManager,
                ssl,
                authProvider,
                properties.getTenantName(),
                Collections.emptyMap());
    }

    /**
     * 单个已注册业务实例的引用计数句柄。
     */
    private final class ActiveRegistration implements Registration {
        private final String key;
        private final String serviceId;
        private final String instanceId;
        private final ScheduledFuture<?> heartbeat;
        private int references = 1;
        private boolean closed;
        /**
         * 创建引用计数的注册句柄。
         *
         * @param key 端点去重键
         * @param serviceId 服务中心微服务标识
         * @param instanceId 服务中心实例标识
         * @param heartbeat 定时心跳任务
         */
        private ActiveRegistration(String key, String serviceId, String instanceId, ScheduledFuture<?> heartbeat) {
            this.key = key;
            this.serviceId = serviceId;
            this.instanceId = instanceId;
            this.heartbeat = heartbeat;
        }
        /**
         * 为该注册增加一个消费者引用。
         *
         * @return 该注册句柄
         */
        private ActiveRegistration acquire() {
            references++;
            return this;
        }

        /** 关闭该资源并释放关联状态。 */
        @Override
        public void close() {
            synchronized (registrationLock) {
                if (closed) {
                    return;
                }
                references--;
                if (references > 0) {
                    return;
                }
                closed = true;
                activeInstances.remove(key, this);
                heartbeat.cancel(false);
                try {
                    client.deleteMicroserviceInstance(serviceId, instanceId);
                    LOGGER.info("Unregistered CSE instance {}", instanceId);
                } catch (RuntimeException e) {
                    LOGGER.warn("Unable to unregister CSE instance {}", instanceId, e);
                }
            }
        }
    }

    /** Nacos 客户端上报的、校验通过的业务端点。 */
    record Endpoint(String host, String value) {
    }
}
