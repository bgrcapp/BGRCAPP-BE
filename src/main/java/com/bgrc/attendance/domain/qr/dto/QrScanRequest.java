package com.bgrc.attendance.domain.qr.dto;

import lombok.Data;

@Data
public class QrScanRequest {
    private String qrData;
    private String deviceId; // FE에서 해당 필드 제거 필요 (실제 출석 로직에 쓰이지 않음)
}
