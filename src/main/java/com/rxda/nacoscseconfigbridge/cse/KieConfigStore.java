package com.rxda.nacoscseconfigbridge.cse;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.Optional;

import org.apache.servicecomb.config.kie.client.KieClient;
import org.apache.servicecomb.config.kie.client.model.ConfigurationsRequest;
import org.apache.servicecomb.config.kie.client.model.ConfigurationsResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.rxda.nacoscseconfigbridge.config.KieClientFactory;
import com.rxda.nacoscseconfigbridge.nacos.NacosConfigKey;

@Service
public class KieConfigStore {

    private final KieClientFactory clientFactory;

    // No-argument constructor used by protocol tests that replace read().
    public KieConfigStore() {
        this.clientFactory = null;
    }

    @Autowired
    public KieConfigStore(KieClientFactory clientFactory) {
        this.clientFactory = clientFactory;
    }

    public ReadResult read(NacosConfigKey key, String revision, boolean longPolling) {
        ConfigurationsRequest request = new ConfigurationsRequest()
                .setWithExact(true)
                .setLabelsQuery(labels(key))
                .setRevision(revision == null ? ConfigurationsRequest.INITIAL_REVISION : revision);
        KieClient client = clientFactory.create(longPolling);
        ConfigurationsResponse response = client.queryConfigurations(request, clientFactory.address());
        Map<String, Object> configurations = response.getConfigurations();
        String content = configurations == null ? null : asString(configurations.get(key.dataId()));
        return new ReadResult(response.isChanged(), response.getRevision(), Optional.ofNullable(content));
    }

    public String md5(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5")
                    .digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(32);
            for (byte item : digest) {
                result.append(String.format("%02x", item));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 is not available", e);
        }
    }

    private String labels(NacosConfigKey key) {
        return "label=" + encode("app:" + clientFactory.app())
                + "&label=" + encode("environment:" + key.effectiveTenant())
                + "&label=" + encode("service:" + key.effectiveGroup())
                + "&label=" + encode("nacos-data-id:" + key.dataId());
    }

    private String encode(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalArgumentException("Unable to encode KIE label", e);
        }
    }

    private String asString(Object value) {
        return value == null ? null : value.toString();
    }

    public record ReadResult(boolean changed, String revision, Optional<String> content) {
    }
}
