package com.rxda.nacoscseconfigbridge.config;

import lombok.RequiredArgsConstructor;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.rxda.nacoscseconfigbridge.web.NacosAuthInterceptor;

/**
 * 把 Nacos HTTP 鉴权拦截器注册到兼容接口和管理接口。
 */
@Configuration
@RequiredArgsConstructor
public class WebConfiguration implements WebMvcConfigurer {

    private final NacosAuthInterceptor authInterceptor;

    /**
     * 保护 Nacos 兼容接口和迁移接口，但允许登录接口本身匿名访问。
     *
     * @param registry Spring MVC 拦截器注册表
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/nacos/**", "/srv-nacos-cse-config-bridge/**")
                .excludePathPatterns(
                        "/nacos/v1/auth/users/login",
                        "/nacos/v1/auth/login");
    }
}
