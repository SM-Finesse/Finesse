package com.finesse.backend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 프론트(별도 포트에서 뜨는 Vite 개발 서버 등)에서 백엔드 API를 호출할 수 있게 CORS를 연다.
 * 로그인이 없는 조회 전용 API라 자격 증명(쿠키 등)은 주고받지 않는다 — allowCredentials 불필요.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(
                        "http://localhost:5173", "http://127.0.0.1:5173", // Vite 기본 개발 포트
                        "http://localhost:4173", "http://127.0.0.1:4173"  // vite preview 기본 포트
                )
                .allowedMethods("GET")
                .allowedHeaders("*");
    }
}
