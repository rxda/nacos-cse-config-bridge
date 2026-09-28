package com.rxda.nacoscseconfigbridge.web;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.RestController;

import com.rxda.nacoscseconfigbridge.nacos.NacosListenerEntry;
import com.rxda.nacoscseconfigbridge.nacos.NacosListenerParser;
import com.rxda.nacoscseconfigbridge.nacos.NacosListenerService;

/**
 * 实现 Nacos HTTP 长轮询监听端点。
 */

@RestController
@RequestMapping("/nacos/v1/cs/configs")
public class NacosListenerController {

    private final NacosListenerParser parser;
    private final NacosListenerService listenerService;
    private final Executor listenerExecutor;
    /**
     * 创建异步的 Nacos 监听控制器。
     *
     * @param parser 监听载荷的解析与序列化器
     * @param listenerService KIE 监听协调器
     * @param nacosListenerExecutor 长轮询请求用的线程池
     */
    public NacosListenerController(
            NacosListenerParser parser,
            NacosListenerService listenerService,
            @Qualifier("nacosListenerExecutor") Executor nacosListenerExecutor) {
        this.parser = parser;
        this.listenerService = listenerService;
        this.listenerExecutor = nacosListenerExecutor;
    }

    /**
     * 检查监听条目并异步等待 KIE 侧的变更。
     *
     * @param body 表单体中的监听载荷
     * @param header 以请求头形式提供的等效监听载荷
     * @return 以 Nacos 监听响应完成的 future
     */
    @PostMapping(value = "/listener", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.TEXT_PLAIN_VALUE)
    public CompletableFuture<ResponseEntity<String>> listen(
            @RequestParam(name = "Listening-Configs", required = false) String body,
            @RequestHeader(name = "Listening-Configs", required = false) String header) {
        String raw = body == null ? header : body;
        List<NacosListenerEntry> entries = parser.parse(raw);
        return CompletableFuture.supplyAsync(() -> {
            List<com.rxda.nacoscseconfigbridge.nacos.NacosConfigKey> changed = listenerService.listen(entries);
            return ResponseEntity.ok(parser.response(changed));
        }, listenerExecutor);
    }
}
