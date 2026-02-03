package com.bgrc.attendance.domain.qr.controller;

import com.bgrc.attendance.global.common.CommonResponse;
import com.bgrc.attendance.domain.qr.dto.QrScanRequest;
import com.bgrc.attendance.domain.qr.dto.QrScanResponse;
import com.bgrc.attendance.domain.qr.service.QrService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/qr") // QR 관련 API의 기본 경로 설정 (클래스 레벨)
@RequiredArgsConstructor // final 필드 생성자 자동 생성
public class QrController {
    private final QrService qrService;

    @PostMapping("/scan")
    public CommonResponse<QrScanResponse> scanQR(@RequestBody QrScanRequest request){
        QrScanResponse response = qrService.scan(request);
        return CommonResponse.success(response);
    }
}
