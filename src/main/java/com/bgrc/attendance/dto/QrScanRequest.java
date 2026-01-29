package com.bgrc.attendance.dto;

import lombok.Data;

@Data
public class QrScanRequest {
    // JSON 필드명과 매핑 (다른 이름일 경우 @JsonProperty 사용)
    private String qrData; // jakarta 라이브러리로 NotBlank 지정하는 것도 좋음
    private String deviceId; // FE에서 해당 필드 제거 필요 (실제 출석 로직에 쓰이지 않음)
}
