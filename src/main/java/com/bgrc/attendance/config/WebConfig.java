package com.bgrc.attendance.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    // WebMvcConfigurer의 웹 설정 기능을 상속받음
    @Override
    public void addCorsMappings(CorsRegistry registry){
        registry.addMapping("/api/qr/**")
                .allowedOrigins("http://localhost:3000") // 프론트엔드 주소
                .allowedMethods("GET", "POST")
                .allowedHeaders("*")
                .allowCredentials(false); // 쿠키, 인증서 등 사용 여부
    }
}
