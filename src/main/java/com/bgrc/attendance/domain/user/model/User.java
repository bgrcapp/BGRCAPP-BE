package com.bgrc.attendance.domain.user.model;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class User {
    private String serialNumber;
    private String name;
    private String birthDate;

    public User(String name, String birthDate) {
        this("", name, birthDate);
    }
}
