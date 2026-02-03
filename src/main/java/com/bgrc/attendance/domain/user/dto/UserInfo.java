package com.bgrc.attendance.domain.user.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class UserInfo {
    private String name;
    private String birthDate;
    private Boolean inRegistry;
}
