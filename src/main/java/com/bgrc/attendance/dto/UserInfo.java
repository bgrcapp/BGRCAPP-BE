package com.bgrc.attendance.dto;

import lombok.Data;

@Data
public class UserInfo {
    private String name;
    private String birthDate;
    private boolean inRegistry;
    private final String welcomeMessage = "환영합니다";
}
