package com.bgrc.attendance.domain.server.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.info.BuildProperties;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ServerController {
    private final BuildProperties buildProperties;

    @GetMapping("/status")
    public ResponseEntity<?> getStatus(){
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "OK");
        response.put("timestamp", LocalDateTime.now());
        response.put("message", "서버가 정상적으로 동작하고 있습니다.");
        response.put("version", buildProperties.getVersion());
        return ResponseEntity.ok(response);
    }

    /** launcher가 새 JAR 기동 성공 여부와 실행 버전을 확인할 때 사용한다. */
    @GetMapping("/version")
    public ResponseEntity<?> getVersion() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("name", buildProperties.getName());
        response.put("version", buildProperties.getVersion());
        response.put("buildTime", buildProperties.getTime());
        return ResponseEntity.ok(response);
    }
}
