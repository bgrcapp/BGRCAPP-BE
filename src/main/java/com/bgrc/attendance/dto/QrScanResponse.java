package com.bgrc.attendance.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor // 기본 생성자
@AllArgsConstructor // 모든 필드 생성자
public class QrScanResponse {
    private boolean success;
    private String message;
    private UserInfo userInfo;
    private LocalDateTime timestamp = getTimestamp();

    public void setse() {

    }
}
