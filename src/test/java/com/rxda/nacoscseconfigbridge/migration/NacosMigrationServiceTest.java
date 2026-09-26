package com.rxda.nacoscseconfigbridge.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.rxda.nacoscseconfigbridge.config.KieClientFactory;
import com.rxda.nacoscseconfigbridge.config.KieProperties;
import com.rxda.nacoscseconfigbridge.nacos.NacosConfigFormat;

class NacosMigrationServiceTest {

    private static final String NACOS = "http://nacos:8848";
    private static final String KIE = "http://cse:30110/v1/default/kie/kv";

    private MockRestServiceServer server;
    private NacosMigrationService service;

    @BeforeEach
    void setUp() {
        KieProperties properties = new KieProperties();
        properties.setServerAddr("http://cse:30110");
        properties.setProject("default");
        properties.setApp("migration-test");
        KieClientFactory factory = new KieClientFactory(properties, List.of());

        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        service = new NacosMigrationService(factory, builder.build());
    }

    @Test
    void allNamespacesDiscoversAndMigratesEveryNamespace() {
        expectNamespaces("""
                {"code":200,"data":[{"namespace":"","namespaceShowName":"public"},
                  {"namespace":"dev","namespaceShowName":"Development"}]}
                """, "source-token");
        expectConfigList("", 1, """
                {"totalCount":1,"pageNumber":1,"pagesAvailable":1,
                 "pageItems":[{"dataId":"public.yaml","group":"DEFAULT_GROUP","tenant":"","content":"public"}]}
                """, "source-token");
        expectConfigList("dev", 1, """
                {"totalCount":1,"pageNumber":1,"pagesAvailable":1,
                 "pageItems":[{"dataId":"app.yaml","group":"APP_GROUP","tenant":"dev","content":"dev"}]}
                """, "source-token");

        expectConfigContent("", "public.yaml", "DEFAULT_GROUP", "public", "source-token");
        expectKieWrite("public.yaml", "public", "DEFAULT_GROUP", "public");
        expectConfigContent("dev", "app.yaml", "APP_GROUP", "dev", "source-token");
        expectKieWrite("app.yaml", "dev", "APP_GROUP", "dev");

        NacosMigrationResponse response = service.migrate(new NacosMigrationRequest(
                NACOS, "ignored-when-all-namespaces-is-true", "source-token", false, true, null));

        assertThat(response.total()).isEqualTo(2);
        assertThat(response.success()).isEqualTo(2);
        assertThat(response.failed()).isZero();
        assertThat(response.items())
                .extracting(NacosMigrationResponse.ItemResult::tenant)
                .containsExactly("public", "dev");
        server.verify();
    }

    @Test
    void omittedConfigsMigratesAllPagesInOneNamespace() {
        String pageOne = configListPage(0, 100, 101, 2);
        String pageTwo = configListPage(100, 1, 101, 2);
        expectConfigList("dev", 1, pageOne, null);
        expectConfigList("dev", 2, pageTwo, null);

        for (int index = 0; index < 101; index++) {
            String dataId = "config-" + index + ".yaml";
            expectConfigContent("dev", dataId, "DEFAULT_GROUP", "content-" + index, null);
            expectKieWrite(dataId, "dev", "DEFAULT_GROUP", "content-" + index);
        }

        NacosMigrationResponse response = service.migrate(new NacosMigrationRequest(
                NACOS, "dev", null, false, false, null));

        assertThat(response.total()).isEqualTo(101);
        assertThat(response.success()).isEqualTo(101);
        assertThat(response.failed()).isZero();
        server.verify();
    }

    @Test
    void explicitConfigsKeepUsingTheExistingSelectionPath() {
        expectConfigContent("dev", "selected.yaml", "SELECTED_GROUP", "selected", null);
        expectKieWrite("selected.yaml", "dev", "SELECTED_GROUP", "selected");

        NacosMigrationResponse response = service.migrate(new NacosMigrationRequest(
                NACOS, "dev", null, false, false,
                List.of(new NacosMigrationRequest.ConfigItem("selected.yaml", "SELECTED_GROUP", null))));

        assertThat(response.total()).isEqualTo(1);
        assertThat(response.success()).isEqualTo(1);
        server.verify();
    }

    @Test
    void discoveredNacosTypeTakesPriorityOverDataIdSuffix() {
        expectConfigList("dev", 1, """
                {"totalCount":1,"pageNumber":1,"pagesAvailable":1,
                 "pageItems":[{"dataId":"settings.conf","group":"DEFAULT_GROUP","tenant":"dev",
                 "type":"json"}]}
                """, null);
        expectConfigContent("dev", "settings.conf", "DEFAULT_GROUP", "{\"enabled\":true}", null);
        expectKieWrite("settings.conf", "dev", "DEFAULT_GROUP", "{\"enabled\":true}", "json");

        NacosMigrationResponse response = service.migrate(new NacosMigrationRequest(
                NACOS, "dev", null, false, false, null));

        assertThat(response.total()).isEqualTo(1);
        assertThat(response.success()).isEqualTo(1);
        server.verify();
    }

    @Test
    void migrationPreservesTypedFormatForPropertiesEpropertiesAndJson() {
        expectConfigContent("dev", "application.properties", "DEFAULT_GROUP", "feature.enabled=true\n", null);
        expectKieWrite("application.properties", "dev", "DEFAULT_GROUP", "feature.enabled=true\n");
        expectConfigContent("dev", "legacy.eproperties", "DEFAULT_GROUP", "feature.legacy=true\n", null);
        expectKieWrite("legacy.eproperties", "dev", "DEFAULT_GROUP", "feature.legacy=true\n");
        expectConfigContent("dev", "application.json", "DEFAULT_GROUP", "{\"enabled\":true}", null);
        expectKieWrite("application.json", "dev", "DEFAULT_GROUP", "{\"enabled\":true}");

        NacosMigrationResponse response = service.migrate(new NacosMigrationRequest(
                NACOS, "dev", null, false, false,
                List.of(
                        new NacosMigrationRequest.ConfigItem("application.properties", null, null),
                        new NacosMigrationRequest.ConfigItem("legacy.eproperties", null, null),
                        new NacosMigrationRequest.ConfigItem("application.json", null, null))));

        assertThat(response.total()).isEqualTo(3);
        assertThat(response.success()).isEqualTo(3);
        server.verify();
    }

    @Test
    void allNamespacesRejectsAnExplicitConfigSelection() {
        NacosMigrationRequest request = new NacosMigrationRequest(
                NACOS, null, null, false, true,
                List.of(new NacosMigrationRequest.ConfigItem("application.yaml", null, null)));

        assertThatThrownBy(() -> service.migrate(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("configs must be empty when allNamespaces is true");
    }

    private void expectNamespaces(String json, String accessToken) {
        server.expect(requestTo(NACOS + "/nacos/v1/console/namespaces?accessToken=" + accessToken))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
    }

    private void expectConfigList(String tenant, int pageNo, String json, String accessToken) {
        var request = server.expect(requestTo(startsWith(NACOS + "/nacos/v1/cs/configs")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(queryParam("search", "blur"))
                .andExpect(queryParam("dataId", ""))
                .andExpect(queryParam("group", ""))
                .andExpect(queryParam("tenant", tenant))
                .andExpect(queryParam("pageNo", String.valueOf(pageNo)))
                .andExpect(queryParam("pageSize", "100"));
        if (accessToken != null) {
            request.andExpect(queryParam("accessToken", accessToken));
        }
        request.andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
    }

    private void expectConfigContent(
            String tenant, String dataId, String group, String content, String accessToken) {
        var request = server.expect(requestTo(startsWith(NACOS + "/nacos/v1/cs/configs")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(queryParam("dataId", dataId))
                .andExpect(queryParam("group", group))
                .andExpect(queryParam("tenant", tenant));
        if (accessToken != null) {
            request.andExpect(queryParam("accessToken", accessToken));
        }
        request.andRespond(withSuccess(content, MediaType.TEXT_PLAIN));
    }

    private void expectKieWrite(String dataId, String environment, String group, String content) {
        expectKieWrite(dataId, environment, group, content, NacosConfigFormat.fromDataId(dataId));
    }

    private void expectKieWrite(
            String dataId, String environment, String group, String content, String valueType) {
        server.expect(requestTo(KIE))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {"key":"%s","value":"%s","value_type":"%s","status":"enabled",
                         "labels":{"app":"migration-test","environment":"%s","service":"%s",
                                   "nacos-data-id":"%s"}}
                        """.formatted(dataId, escapeJson(content), valueType,
                                environment, group, dataId)))
                .andRespond(withSuccess());
    }

    private String escapeJson(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\t", "\\t");
    }

    private String configListPage(int offset, int itemCount, int totalCount, int pagesAvailable) {
        StringBuilder json = new StringBuilder("{\"totalCount\":")
                .append(totalCount)
                .append(",\"pageNumber\":")
                .append(offset / 100 + 1)
                .append(",\"pagesAvailable\":")
                .append(pagesAvailable)
                .append(",\"pageItems\":[");
        for (int index = 0; index < itemCount; index++) {
            if (index > 0) {
                json.append(',');
            }
            int absolute = offset + index;
            json.append("{\"dataId\":\"config-")
                    .append(absolute)
                    .append(".yaml\",\"group\":\"DEFAULT_GROUP\",\"tenant\":\"dev\",")
                    .append("\"content\":\"content-")
                    .append(absolute)
                    .append("\"}");
        }
        return json.append("]}").toString();
    }
}
