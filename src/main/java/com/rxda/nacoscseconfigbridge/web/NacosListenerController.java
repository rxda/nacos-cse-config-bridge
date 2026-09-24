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
import org.springframework.web.bind.annotation.RestController;

import com.rxda.nacoscseconfigbridge.nacos.NacosListenerEntry;
import com.rxda.nacoscseconfigbridge.nacos.NacosListenerParser;
import com.rxda.nacoscseconfigbridge.nacos.NacosListenerService;

@RestController
@RequestMapping("/nacos/v1/cs/configs")
public class NacosListenerController {

    private final NacosListenerParser parser;
    private final NacosListenerService listenerService;
    private final Executor listenerExecutor;

    public NacosListenerController(
            NacosListenerParser parser,
            NacosListenerService listenerService,
            Executor nacosListenerExecutor) {
        this.parser = parser;
        this.listenerService = listenerService;
        this.listenerExecutor = nacosListenerExecutor;
    }

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
