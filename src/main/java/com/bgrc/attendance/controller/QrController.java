package com.bgrc.attendance.controller;

import com.bgrc.attendance.dto.QrScanRequest;
import com.bgrc.attendance.dto.QrScanResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;

@RestController
@RequestMapping("/api/qr") //QR 관련 API의 기본 경로 설정 (클래스 레벨)
public class QrController {
    @GetMapping("/status")
    public ResponseEntity<?> getStatus(){
        return ResponseEntity.ok(Map.of(
                "status", "OK",
                "timestamp", LocalDateTime.now(),
                "message", "서버가 정상적으로 동작하고 있습니다."
        ));
    }

    @PostMapping("/scan")
    public QrScanResponse scanQR(@RequestBody QrScanRequest request){
        //@RequestBody 어노테이션으로 json 데이터를 객체로 변환
        //private QrScanResponse response;
        return null;
    }

}
