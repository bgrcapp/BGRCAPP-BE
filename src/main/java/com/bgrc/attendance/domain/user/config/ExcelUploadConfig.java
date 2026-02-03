package com.bgrc.attendance.domain.user.config;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration
@Getter
public class ExcelUploadConfig {
    @Value("${file.upload.dir}")
    private String uploadDir;
}
