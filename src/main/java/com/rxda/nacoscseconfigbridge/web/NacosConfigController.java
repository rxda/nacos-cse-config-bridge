package com.rxda.nacoscseconfigbridge.web;

import lombok.RequiredArgsConstructor;

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
 * 以 KIE 为后端，只读的 Nacos HTTP Config API。
 */

@RestController
@RequestMapping("/nacos/v1/cs/configs")
@RequiredArgsConstructor
public class NacosConfigController {

    private final KieConfigStore configStore;

    /**
     * 按原始文本读取一个配置文档。
     *
     * @param dataId Nacos dataId
     * @param group Nacos group
     * @param tenant Nacos tenant
     * @return 配置内容，或 HTTP 404
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
     * 拒绝写操作，因为本网桥有意只暴露只读 API。
     *
     * @return HTTP 405
     */
    @PostMapping
    public ResponseEntity<Void> rejectPublish() {
        return ResponseEntity.status(405).build();
    }
    /**
     * 拒绝删除，因为本网桥有意只暴露只读 API。
     *
     * @return HTTP 405
     */
    @DeleteMapping
    public ResponseEntity<Void> rejectDelete() {
        return ResponseEntity.status(405).build();
    }
}
