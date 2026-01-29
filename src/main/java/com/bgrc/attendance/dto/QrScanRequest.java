package com.bgrc.attendance.dto;

import lombok.Data;

@Data
public class QrScanRequest {
    //JSON 필드명과 매핑 (다른 이름일 경우 @JsonProperty 사용)
    private String qrData;
    private String deviceId;
}
