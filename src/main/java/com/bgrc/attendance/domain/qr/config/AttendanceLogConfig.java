package com.bgrc.attendance.domain.qr.config;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Getter
@Configuration
public class AttendanceLogConfig {
    @Value("${attendance.log.dir}")
    private String logDir;

    @Value("${attendance.log.template:}")
    private String templatePath;

    @Value("${attendance.log.monthly-dir:}")
    private String monthlyDir;

    @Value("${attendance.log.sheets:내역1,내역2}")
    private String sheetNames;
}
