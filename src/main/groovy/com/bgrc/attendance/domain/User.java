package com.bgrc.attendance.domain;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class User {
    private String name;
    private String birthDate;
    private String isActive;
}
