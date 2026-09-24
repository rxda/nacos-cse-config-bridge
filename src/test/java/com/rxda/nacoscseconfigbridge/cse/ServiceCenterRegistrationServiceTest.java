package com.rxda.nacoscseconfigbridge.cse;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.apache.servicecomb.service.center.client.ServiceCenterClient;
import org.apache.servicecomb.service.center.client.model.Microservice;
import org.apache.servicecomb.service.center.client.model.MicroserviceInstance;
import org.apache.servicecomb.service.center.client.model.RegisteredMicroserviceInstanceResponse;
import org.apache.servicecomb.service.center.client.model.RegisteredMicroserviceResponse;

import com.alibaba.nacos.api.common.Constants;
import com.rxda.nacoscseconfigbridge.config.ServiceCenterProperties;
import com.rxda.nacoscseconfigbridge.nacos.NacosClientRegistration;

class ServiceCenterRegistrationServiceTest {

    private ServiceCenterProperties properties;
    private RecordingClient client;
    private ScheduledExecutorService scheduler;
    private RecordingFuture heartbeat;
    private ServiceCenterRegistrationService service;

    @BeforeEach
    void setUp() {
        properties = new ServiceCenterProperties();
        properties.setEnabled(true);
        properties.setServerAddr("http://service-center:30100");
        properties.setEnvironment("testing");

        client = new RecordingClient();
        scheduler = recordingScheduler();
        service = new ServiceCenterRegistrationService(properties, client, scheduler);
    }

    @Test
    void disabledRegistrationDoesNothing() {
        properties.setEnabled(false);

        NacosClientRegistration.Registration registration = service.register(
                "dev", Map.of(Constants.APPNAME, "orders"), "10.0.0.12");
        registration.close();

        assertThat(client.interactionCount()).isZero();
        assertThat(heartbeat == null).isTrue();
    }

    @Test
    void clientWithoutAppNameIsIgnored() {
        NacosClientRegistration.Registration registration = service.register(
                "dev", Map.of("someLabel", "value"), "10.0.0.12");
        registration.close();

        assertThat(client.interactionCount()).isZero();
        assertThat(heartbeat == null).isTrue();
    }

    @Test
    void registersServiceOnlyWhenBusinessPortIsMissing() {
        NacosClientRegistration.Registration registration = service.register(
                "tenant-a", Map.of(Constants.APPNAME, "orders"), "10.0.0.12");
        registration.close();

        assertThat(client.queries).hasSize(1);
        assertThat(client.queries.getFirst().getServiceName()).isEqualTo("orders");
        assertThat(client.queries.getFirst().getEnvironment()).isEqualTo("testing");
        assertThat(client.registeredInstances).isEmpty();
        assertThat(client.deletedInstances).isEmpty();
        assertThat(heartbeat == null).isTrue();
    }

    @Test
    void invalidOrEmptyNacosTenantUsesConfiguredCseEnvironment() {
        service.register("", Map.of(Constants.APPNAME, "orders"), "10.0.0.12").close();
        service.register("public", Map.of(Constants.APPNAME, "orders"), "10.0.0.13").close();
        service.register("dwyzt", Map.of(Constants.APPNAME, "orders"), "10.0.0.14").close();

        assertThat(client.queries).hasSize(3);
        assertThat(client.queries)
                .extracting(Microservice::getEnvironment)
                .containsOnly("testing");
    }

    @Test
    void supportedCseEnvironmentIsPreserved() {
        service.register("production", Map.of(Constants.APPNAME, "orders"), "10.0.0.12").close();

        assertThat(client.queries).hasSize(1);
        assertThat(client.queries.getFirst().getEnvironment()).isEqualTo("production");
    }

    @Test
    void invalidConfiguredEnvironmentFallsBackToDevelopment() {
        properties.setEnvironment("fallback-env");

        service.register("", Map.of(Constants.APPNAME, "orders"), "10.0.0.12").close();

        assertThat(client.queries).hasSize(1);
        assertThat(client.queries.getFirst().getEnvironment()).isEqualTo("development");
    }

    @Test
    void servicePortLabelCreatesBusinessInstanceFromRemoteIp() {
        NacosClientRegistration.Registration registration = service.register(
                "dev",
                Map.of(
                        Constants.APPNAME, "orders",
                        "servicePort", "8080",
                        "serviceProtocol", "https"),
                "10.0.0.12");

        assertThat(client.registeredInstances).hasSize(1);
        MicroserviceInstance instance = client.registeredInstances.getFirst();
        assertThat(instance.getHostName()).isEqualTo("10.0.0.12");
        assertThat(instance.getEndpoints()).containsExactly("https://10.0.0.12:8080");
        assertThat(heartbeat != null).isTrue();

        registration.close();
        assertThat(heartbeat.isCancelled()).isTrue();
        assertThat(client.deletedInstances).containsExactly("service-id|instance-id");
    }

    @Test
    void standardNacosAppPrefixedLabelsCreateExplicitBusinessInstance() {
        NacosClientRegistration.Registration registration = service.register(
                "dev",
                Map.of(
                        Constants.APPNAME, "orders",
                        "app_serviceHost", "10.20.30.40",
                        "app_servicePort", "8443",
                        "app_serviceProtocol", "https"),
                "10.0.0.12");

        assertThat(client.registeredInstances).hasSize(1);
        MicroserviceInstance instance = client.registeredInstances.getFirst();
        assertThat(instance.getHostName()).isEqualTo("10.20.30.40");
        assertThat(instance.getEndpoints()).containsExactly("https://10.20.30.40:8443");

        registration.close();
    }

    @Test
    void fullEndpointLabelIsIgnoredByDefault() {
        NacosClientRegistration.Registration registration = service.register(
                "dev",
                Map.of(
                        Constants.APPNAME, "orders",
                        "cse.instance.endpoint", "http://10.10.10.10:9090/health"),
                "10.0.0.12");
        registration.close();

        assertThat(client.queries).hasSize(1);
        assertThat(client.registeredInstances).isEmpty();
        assertThat(heartbeat == null).isTrue();
    }

    @Test
    void explicitEndpointLabelOverridesRemoteIpAndPortWhenEnabled() {
        properties.setEndpointLabel("cse.instance.endpoint");

        NacosClientRegistration.Registration registration = service.register(
                "dev",
                Map.of(
                        Constants.APPNAME, "orders",
                        "cse.instance.endpoint", "http://10.10.10.10:9090/health"),
                "10.0.0.12");

        assertThat(client.registeredInstances).hasSize(1);
        MicroserviceInstance instance = client.registeredInstances.getFirst();
        assertThat(instance.getHostName()).isEqualTo("10.10.10.10");
        assertThat(instance.getEndpoints())
                .containsExactly("http://10.10.10.10:9090/health");

        registration.close();
    }

    @Test
    void sameEndpointIsSharedByMultipleConfigConnections() {
        Map<String, String> labels = Map.of(
                Constants.APPNAME, "orders",
                "servicePort", "8080");

        NacosClientRegistration.Registration first = service.register("dev", labels, "10.0.0.12");
        NacosClientRegistration.Registration second = service.register("dev", labels, "10.0.0.12");

        assertThat(client.registeredInstances).hasSize(1);
        first.close();
        assertThat(client.deletedInstances).isEmpty();

        second.close();
        assertThat(client.deletedInstances).containsExactly("service-id|instance-id");
        assertThat(heartbeat.isCancelled()).isTrue();
    }

    @SuppressWarnings("unchecked")
    private ScheduledExecutorService recordingScheduler() {
        return (ScheduledExecutorService) Proxy.newProxyInstance(
                ScheduledExecutorService.class.getClassLoader(),
                new Class<?>[] {ScheduledExecutorService.class},
                (proxy, method, args) -> {
                    if ("scheduleAtFixedRate".equals(method.getName())
                            && method.getParameterCount() == 4
                            && args[0] instanceof Runnable) {
                        heartbeat = new RecordingFuture();
                        return heartbeat;
                    }
                    if (method.getDeclaringClass() == Object.class) {
                        return method.invoke(this, args);
                    }
                    throw new UnsupportedOperationException("Unexpected scheduler call: " + method.getName());
                });
    }

    private static RegisteredMicroserviceResponse response(String serviceId) {
        RegisteredMicroserviceResponse response = new RegisteredMicroserviceResponse();
        response.setServiceId(serviceId);
        return response;
    }

    private static RegisteredMicroserviceInstanceResponse instanceResponse(String instanceId) {
        RegisteredMicroserviceInstanceResponse response = new RegisteredMicroserviceInstanceResponse();
        response.setInstanceId(instanceId);
        return response;
    }

    private static final class RecordingClient extends ServiceCenterClient {
        private final List<Microservice> queries = new ArrayList<>();
        private final List<MicroserviceInstance> registeredInstances = new ArrayList<>();
        private final List<String> deletedInstances = new ArrayList<>();
        private int heartbeats;

        private RecordingClient() {
            super(null, null);
        }

        @Override
        public RegisteredMicroserviceResponse queryServiceId(Microservice service) {
            queries.add(service);
            return response("service-id");
        }

        @Override
        public RegisteredMicroserviceInstanceResponse registerMicroserviceInstance(MicroserviceInstance instance) {
            registeredInstances.add(instance);
            return instanceResponse("instance-id");
        }

        @Override
        public void deleteMicroserviceInstance(String serviceId, String instanceId) {
            deletedInstances.add(serviceId + "|" + instanceId);
        }

        @Override
        public boolean sendHeartBeat(String serviceId, String instanceId) {
            heartbeats++;
            return true;
        }

        private int interactionCount() {
            return queries.size() + registeredInstances.size() + deletedInstances.size() + heartbeats;
        }
    }

    private static final class RecordingFuture implements ScheduledFuture<Object> {
        private boolean cancelled;

        @Override
        public long getDelay(TimeUnit unit) {
            return 0;
        }

        @Override
        public int compareTo(java.util.concurrent.Delayed other) {
            return 0;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            cancelled = true;
            return true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public boolean isDone() {
            return cancelled;
        }

        @Override
        public Object get() throws ExecutionException {
            throw new ExecutionException(new UnsupportedOperationException());
        }

        @Override
        public Object get(long timeout, TimeUnit unit) throws ExecutionException {
            throw new ExecutionException(new UnsupportedOperationException());
        }
    }
}
