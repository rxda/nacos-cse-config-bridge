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

@RestController
@RequestMapping("/nacos/v1/cs/configs")
public class NacosConfigController {

    private final KieConfigStore configStore;

    public NacosConfigController(KieConfigStore configStore) {
        this.configStore = configStore;
    }

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

    @PostMapping
    public ResponseEntity<Void> rejectPublish() {
        return ResponseEntity.status(405).build();
    }

    @DeleteMapping
    public ResponseEntity<Void> rejectDelete() {
        return ResponseEntity.status(405).build();
    }
}
