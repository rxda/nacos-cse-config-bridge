package com.rxda.nacoscseconfigbridge.web;

import java.io.IOException;
import java.util.Map;

import lombok.RequiredArgsConstructor;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rxda.nacoscseconfigbridge.auth.NacosAuthService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 为 HTTP 兼容接口校验 Nacos 登录令牌。
 */
@Component
@RequiredArgsConstructor
public class NacosAuthInterceptor implements HandlerInterceptor {

    private final NacosAuthService authService;
    private final ObjectMapper objectMapper;

    /**
     * 在业务控制器执行前校验令牌。
     *
     * @param request 当前 HTTP 请求
     * @param response 当前 HTTP 响应
     * @param handler 已匹配的处理器
     * @return 校验通过时返回 {@code true}
     * @throws Exception 响应写出失败时抛出
     */
    @Override
    public boolean preHandle(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler) throws Exception {
        if (authService.isTokenValid(extractToken(request))) {
            return true;
        }
        reject(response);
        return false;
    }

    private String extractToken(HttpServletRequest request) {
        String token = request.getParameter("accessToken");
        if (StringUtils.hasText(token)) {
            return token;
        }
        token = request.getHeader("accessToken");
        if (StringUtils.hasText(token)) {
            return token;
        }
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith("Bearer ")) {
            return authorization.substring("Bearer ".length());
        }
        return null;
    }

    private void reject(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader("WWW-Authenticate", "Bearer");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(),
                Map.of("message", "invalid or missing accessToken"));
    }
}
