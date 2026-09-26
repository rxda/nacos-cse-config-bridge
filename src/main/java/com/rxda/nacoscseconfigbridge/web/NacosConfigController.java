package com.rxda.nacoscseconfigbridge.web;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.rxda.nacoscseconfigbridge.cse.KieConfigStore;
import com.rxda.nacoscseconfigbridge.nacos.NacosConfigKey;

/**
 * Exposes the read-only Nacos HTTP Config API backed by KIE.
 */

@RestController
@RequestMapping("/nacos/v1/cs/configs")
public class NacosConfigController {

    private final KieConfigStore configStore;
    /**
     * Creates the read-only Nacos Config HTTP controller.
     *
     * @param configStore exact KIE-backed configuration store
     */
    public NacosConfigController(KieConfigStore configStore) {
        this.configStore = configStore;
    }
    /**
     * Reads one configuration document as its original text.
     *
     * @param dataId Nacos dataId
     * @param group Nacos group
     * @param tenant Nacos tenant
     * @return configuration content or HTTP 404
     */
    @GetMapping(produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> getConfig(
            @RequestParam String dataId,
            @RequestParam(defaultValue = "DEFAULT_GROUP") String group,
            @RequestParam(defaultValue = "public") String tenant) {
        NacosConfigKey key = new NacosConfigKey(dataId, group, tenant);
        KieConfigStore.ReadResult result = configStore.read(key, null, false);
        return result.content()
                .map(content -> ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(content))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
    /**
     * Rejects writes because this bridge intentionally exposes a read-only API.
     *
     * @return HTTP 405
     */
    @PostMapping
    public ResponseEntity<Void> rejectPublish() {
        return ResponseEntity.status(405).build();
    }
    /**
     * Rejects deletes because this bridge intentionally exposes a read-only API.
     *
     * @return HTTP 405
     */
    @DeleteMapping
    public ResponseEntity<Void> rejectDelete() {
        return ResponseEntity.status(405).build();
    }
}
