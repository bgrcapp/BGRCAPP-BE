package com.bgrc.attendance.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    // WebMvcConfigurer의 웹 설정 기능을 상속받음
    @Override
    public void addCorsMappings(CorsRegistry registry){
        registry.addMapping("/api/**")
                .allowedOrigins("http://localhost:3000") // apk FE 주소
                .allowedOrigins("http://localhost:5500") // open live 주소
                .allowedMethods("GET", "POST")
                .allowedHeaders("*")
                .allowCredentials(false); // 쿠키, 인증서 등 사용 여부
    }
}
