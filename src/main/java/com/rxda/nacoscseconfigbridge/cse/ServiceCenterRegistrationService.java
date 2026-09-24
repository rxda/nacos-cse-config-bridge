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
 * Observes Nacos Config gRPC clients and mirrors them into CSE Service Center.
 *
 * <p>A Config client is not a service-discovery client. Therefore this class
 * creates a microservice for display and only creates an instance when the
 * client explicitly supplies business endpoint labels. It never treats the
 * Nacos gRPC source port as the application's port.</p>
 */
@Service
public class ServiceCenterRegistrationService implements NacosClientRegistration {

    private static final Logger LOGGER = LoggerFactory.getLogger(ServiceCenterRegistrationService.class);
    private static final String NACOS_SOURCE = "nacos-config-proxy";
    private static final String DEFAULT_PROTOCOL = "http";
    private static final String DEFAULT_CSE_ENVIRONMENT = "development";
    private static final Set<String> CSE_ENVIRONMENTS =
            Set.of("development", "testing", "acceptance", "production");

    private final ServiceCenterProperties properties;
    private final ScheduledExecutorService scheduler;
    private final ServiceCenterClient client;
    private final Map<String, ActiveRegistration> activeInstances = new HashMap<>();
    private final Object registrationLock = new Object();

    @Autowired
    public ServiceCenterRegistrationService(
            ServiceCenterProperties properties,
            List<AuthHeaderProvider> authHeaderProviders,
            @Qualifier("nacosServiceCenterExecutor") ScheduledExecutorService scheduler) {
        this.properties = properties;
        this.scheduler = scheduler;
        this.client = properties.isEnabled() ? createClient(properties, authHeaderProviders) : null;
        if (properties.isEnabled()) {
            LOGGER.info("CSE Service Center observation registration is enabled: {}", properties.getServerAddr());
        }
    }

    ServiceCenterRegistrationService(
            ServiceCenterProperties properties,
            ServiceCenterClient client,
            ScheduledExecutorService scheduler) {
        this.properties = properties;
        this.client = client;
        this.scheduler = scheduler;
    }

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
            // Service Center must not take down the Nacos Config compatibility path.
            LOGGER.warn("Unable to mirror Nacos client {} into CSE Service Center", serviceName, e);
            return () -> {
            };
        }
    }

    private String ensureMicroservice(String serviceName, String tenant, Map<String, String> labels) {
        Microservice service = new Microservice();
        service.setAppId(properties.getAppId());
        service.setServiceName(serviceName);
        service.setVersion(properties.getVersion());
        service.setEnvironment(environment(tenant));
        service.setRegisterBy(NACOS_SOURCE);
        service.setStatus(MicroserviceStatus.UP);

        Framework framework = new Framework();
        framework.setName("Nacos Config");
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
            // A concurrent proxy/client may have created it between query and create.
            RegisteredMicroserviceResponse retry = client.queryServiceId(service);
            if (retry == null || !StringUtils.hasText(retry.getServiceId())) {
                throw new IllegalStateException("CSE did not return a microservice id");
            }
            return retry.getServiceId();
        }
        return created.getServiceId();
    }

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

    private void sendHeartbeat(String serviceName, String serviceId, String instanceId) {
        try {
            if (!client.sendHeartBeat(serviceId, instanceId)) {
                LOGGER.warn("CSE heartbeat rejected for {} instance {}", serviceName, instanceId);
            }
        } catch (RuntimeException e) {
            LOGGER.warn("CSE heartbeat failed for {} instance {}", serviceName, instanceId, e);
        }
    }

    private Endpoint endpoint(Map<String, String> labels, String remoteHost) {
        String explicit = value(labels, properties.getEndpointLabel());
        if (StringUtils.hasText(explicit)) {
            return parseExplicitEndpoint(explicit);
        }

        String portText = value(labels, properties.getPortLabel());
        String host = value(labels, properties.getHostLabel(), remoteHost);
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

    private static String hostForUri(String host) {
        return host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
    }

    private static String unbracketHost(String host) {
        return host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
    }

    /**
     * Reads a configured connection label. Nacos 2.5.2 adds {@code app_} to
     * every key from {@code nacos.app.conn.labels}, so accept both forms.
     */
    private static String value(Map<String, String> labels, String key) {
        if (!StringUtils.hasText(key)) {
            return null;
        }
        String direct = labels.get(key);
        if (StringUtils.hasText(direct)) {
            return direct;
        }
        return labels.get("app_" + key);
    }

    private static String value(Map<String, String> labels, String key, String fallback) {
        String value = value(labels, key);
        return StringUtils.hasText(value) ? value : fallback;
    }

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

    private final class ActiveRegistration implements Registration {
        private final String key;
        private final String serviceId;
        private final String instanceId;
        private final ScheduledFuture<?> heartbeat;
        private int references = 1;
        private boolean closed;

        private ActiveRegistration(String key, String serviceId, String instanceId, ScheduledFuture<?> heartbeat) {
            this.key = key;
            this.serviceId = serviceId;
            this.instanceId = instanceId;
            this.heartbeat = heartbeat;
        }

        private ActiveRegistration acquire() {
            references++;
            return this;
        }

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

    record Endpoint(String host, String value) {
    }
}
