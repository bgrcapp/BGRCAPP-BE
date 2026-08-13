package com.bgrc.attendance.domain.server.component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** 서버 JAR가 실제 기동된 버전을 콘솔과 운영 로그에 명확히 남긴다. */
@Component
@RequiredArgsConstructor
@Slf4j
public class ServerVersionStartupLogger {
    private final BuildProperties buildProperties;

    @EventListener(ApplicationReadyEvent.class)
    public void logRunningVersion() {
        log.info("==================================================");
        log.info("무료급식 출석 서버 실행 완료 | 버전 {}", buildProperties.getVersion());
        log.info("==================================================");
    }
}
