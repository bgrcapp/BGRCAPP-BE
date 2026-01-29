package com.bgrc.attendance.model;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class User {
    private String name;
    private String birthDate;
    private String isActive;
}
