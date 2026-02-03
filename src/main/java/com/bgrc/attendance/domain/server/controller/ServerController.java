package com.bgrc.attendance.domain.server.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.time.LocalDateTime;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ServerController {

    @GetMapping("/status")
    public ResponseEntity<?> getStatus(){
        return ResponseEntity.ok(Map.of(
                "status", "OK",
                "timestamp", LocalDateTime.now(),
                "message", "서버가 정상적으로 동작하고 있습니다."
        ));
    }
}
