package com.bgrc.attendance.domain.qr.config;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Getter
@Configuration
public class HolidayApiConfig {
    @Value("${attendance.holiday-api.url:https://apis.data.go.kr/B090041/openapi/service/SpcdeInfoService/getRestDeInfo}")
    private String url;

    /** 공공데이터포털에서 발급한 인증키. 소스에는 저장하지 않고 환경 변수로 주입한다. */
    @Value("${attendance.holiday-api.service-key:}")
    private String serviceKey;

    @Value("${attendance.holiday-api.cache-dir:./data/attendance/holidays}")
    private String cacheDir;
}
