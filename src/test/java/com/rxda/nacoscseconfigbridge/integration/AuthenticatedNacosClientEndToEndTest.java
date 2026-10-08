package com.rxda.nacoscseconfigbridge.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.config.ConfigFactory;
import com.alibaba.nacos.api.config.ConfigService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import com.rxda.nacoscseconfigbridge.NacosCseConfigBridgeApplication;
import com.rxda.nacoscseconfigbridge.web.NacosAuthController;

/**
 * Boots the bridge against a fake KIE backend and lets a real Nacos client
 * that carries a username and password read a configuration through it.
 *
 * <p>This is the regression test for the broken case: authenticated clients
 * log in on {@code /nacos/v1/auth/users/login} before reading anything, and
 * that endpoint used to answer HTTP 404.</p>
 */
class AuthenticatedNacosClientEndToEndTest {

    private static final String DATA_ID = "application.yaml";
    private static final String GROUP = "DEFAULT_GROUP";
    private static final String CONTENT = "feature.enabled: true\n";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static HttpServer kie;
    private static ConfigurableApplicationContext bridge;
    private static int httpPort;
    private static int grpcPort;
    private static int kiePort;

    @BeforeAll
    static void startBridge() throws Exception {
        kiePort = freePort();
        kie = startFakeKie(kiePort);
        // Nacos clients derive the gRPC port from the HTTP port by adding 1000,
        // so both ports have to be reserved together.
        httpPort = freePortWithOffset(1000);
        grpcPort = httpPort + 1000;
        bridge = new SpringApplicationBuilder(NacosCseConfigBridgeApplication.class)
                .web(WebApplicationType.SERVLET)
                // Command-line arguments outrank application.yml, where the
                // KIE address defaults to an empty placeholder.
                .run(
                        "--server.port=" + httpPort,
                        "--srv-nacos-cse-config-bridge.grpc.port=" + grpcPort,
                        "--srv-nacos-cse-config-bridge.kie.server-addr=http://127.0.0.1:" + kiePort,
                        "--srv-nacos-cse-config-bridge.kie.project=default",
                        "--srv-nacos-cse-config-bridge.kie.app=nacos-config-bridge",
                        "--srv-nacos-cse-config-bridge.service-center.enabled=false",
                        "--srv-nacos-cse-config-bridge.migration.enabled=false",
                        "--spring.main.banner-mode=off");
    }

    @AfterAll
    static void stopBridge() {
        if (bridge != null) {
            bridge.close();
        }
        if (kie != null) {
            kie.stop(0);
        }
    }

    @Test
    void authenticatedClientLogsInAndReadsConfiguration() throws Exception {
        assertThat(login()).isEqualTo(200);

        Properties properties = new Properties();
        properties.setProperty(PropertyKeyConst.SERVER_ADDR, "127.0.0.1:" + httpPort);
        properties.setProperty(PropertyKeyConst.USERNAME, "nacos");
        properties.setProperty(PropertyKeyConst.PASSWORD, "nacos-secret");
        properties.setProperty(PropertyKeyConst.CONFIG_REQUEST_TIMEOUT, "10000");

        ConfigService client = ConfigFactory.createConfigService(properties);
        try {
            assertThat(client.getConfig(DATA_ID, GROUP, 10_000)).isEqualTo(CONTENT);
        } finally {
            client.shutDown();
        }
    }

    @Test
    void loginAnswersWithTheFieldsNacosClientsParse() throws Exception {
        int status = login();
        assertThat(status).isEqualTo(200);
    }

    /** Sends the login request the Nacos client sends: query and form fields. */
    private int login() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + httpPort + "/nacos/v1/auth/users/login?username=nacos"))
                .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString("password=nacos-secret&"))
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() == 200) {
            JsonNode body = MAPPER.readTree(response.body());
            assertThat(body.get("accessToken").asText()).isNotBlank();
            assertThat(body.get("tokenTtl").asLong())
                    .isEqualTo(NacosAuthController.TOKEN_TTL_SECONDS);
        }
        return response.statusCode();
    }

    /** Serves one KIE document built from the labels the bridge requested. */
    private static HttpServer startFakeKie(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", AuthenticatedNacosClientEndToEndTest::serveKie);
        server.setExecutor(Executors.newFixedThreadPool(2));
        server.start();
        return server;
    }

    private static void serveKie(HttpExchange exchange) throws IOException {
        Map<String, String> labels = new LinkedHashMap<>();
        for (String pair : queryValues(exchange.getRequestURI().getRawQuery(), "label")) {
            int separator = pair.indexOf(':');
            if (separator > 0) {
                labels.put(pair.substring(0, separator), pair.substring(separator + 1));
            }
        }
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("key", labels.getOrDefault("nacos-data-id", ""));
        document.put("value", CONTENT);
        document.put("valueType", "yaml");
        document.put("labels", labels);
        document.put("updateTime", 1L);
        document.put("status", "enabled");
        byte[] payload = MAPPER.writeValueAsString(Map.of("data", List.of(document)))
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("X-Kie-Revision", "1");
        exchange.sendResponseHeaders(200, payload.length);
        try (OutputStream body = exchange.getResponseBody()) {
            body.write(payload);
        }
    }

    private static List<String> queryValues(String rawQuery, String name) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return List.of();
        }
        List<String> values = new java.util.ArrayList<>();
        for (String pair : rawQuery.split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts.length == 2 && name.equals(parts[0])) {
                values.add(URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
            }
        }
        return values;
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static int freePortWithOffset(int offset) throws IOException {
        for (int attempt = 0; attempt < 64; attempt++) {
            int port = freePort();
            if (port + offset < 65000 && isFree(port + offset)) {
                return port;
            }
        }
        throw new IllegalStateException("Unable to reserve a free port pair");
    }

    private static boolean isFree(int port) {
        try (ServerSocket socket = new ServerSocket(port)) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
