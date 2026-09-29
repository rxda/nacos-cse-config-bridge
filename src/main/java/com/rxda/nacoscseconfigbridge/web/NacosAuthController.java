package com.rxda.nacoscseconfigbridge.web;

import java.util.Map;
import java.util.Optional;

import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.rxda.nacoscseconfigbridge.auth.NacosAuthService;
import com.rxda.nacoscseconfigbridge.config.NacosAuthProperties;

/**
 * 提供 Nacos 客户端使用的用户名密码登录接口。
 */
@RestController
@RequestMapping("/nacos/v1/auth")
@RequiredArgsConstructor
public class NacosAuthController {

    private final NacosAuthService authService;
    private final NacosAuthProperties properties;

    /**
     * 接受 Nacos 2.x 客户端登录，并返回其期望的令牌字段。
     *
     * @param username 用户名
     * @param password 密码
     * @return 登录成功后的令牌，或 HTTP 403
     */
    @PostMapping(value = {"/users/login", "/login"},
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<Map<String, Object>> login(
            @RequestParam String username,
            @RequestParam String password) {
        Optional<String> token = authService.login(username, password);
        if (token.isEmpty()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("message", "username or password is invalid"));
        }
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "accessToken", token.get(),
                        "tokenTtl", properties.getTokenTtlSeconds(),
                        "username", username,
                        "globalAdmin", true));
    }
}
