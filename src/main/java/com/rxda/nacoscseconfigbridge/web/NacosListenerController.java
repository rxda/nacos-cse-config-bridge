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
 * Implements the Nacos HTTP long-poll listener endpoint.
 */

@RestController
@RequestMapping("/nacos/v1/cs/configs")
public class NacosListenerController {

    private final NacosListenerParser parser;
    private final NacosListenerService listenerService;
    private final Executor listenerExecutor;
    /**
     * Creates the asynchronous Nacos listener controller.
     *
     * @param parser listener payload parser and serializer
     * @param listenerService KIE-backed listener coordinator
     * @param nacosListenerExecutor executor for long-poll requests
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
     * Checks listener entries and waits asynchronously for KIE-backed changes.
     *
     * @param body form-body listener payload
     * @param header equivalent listener payload supplied as a header
     * @return future completed with the Nacos listener response
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
