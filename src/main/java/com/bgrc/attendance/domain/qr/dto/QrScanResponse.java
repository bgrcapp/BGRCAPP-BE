package com.bgrc.attendance.domain.qr.dto;

import com.bgrc.attendance.domain.user.dto.UserInfo;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class QrScanResponse {
    private UserInfo userInfo;
    private String welcomeMessage;
}
