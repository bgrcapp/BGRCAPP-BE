package com.bgrc.attendance.domain.user.model;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class User {
    private String name;
    private String birthDate;
    // 향후 확장성을 고려하여 User를 객체로 추상화
}
