package com.qskj.get_geo_pg.config;

import com.qskj.get_geo_pg.interceptor.JwtInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Autowired
    private JwtInterceptor jwtInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(jwtInterceptor)
                .addPathPatterns("/geo/**", "/excalidraw/**", "/fungis_user/**", "/hhgl_user/**", "/user/**") // 保护主要业务及模块 API
                .excludePathPatterns(
                        "/hhgl_user/login", "/fungis_user/login", "/user/login", "/excalidraw/user/login", "/jwt/**",
                        "/excalidraw/nav/list", "/excalidraw/listByType", "/excalidraw/getByBoardName", "/excalidraw/list", "/excalidraw/get/*",
                        "/geo/route/**"
                ); // 排除登录、公钥获取及路径规划等公开接口
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
