package com.bgrc.attendance.domain.user.model;

import lombok.Getter;

@Getter
public class User {
    private String serialNumber;
    private String name;
    private String birthDate;
    /** 월별 일지의 기존 전화번호 열과 명단의 생년월일 PK를 연결할 때만 사용한다. */
    private String phoneNumber;

    public User(String serialNumber, String name, String birthDate, String phoneNumber) {
        this.serialNumber = serialNumber;
        this.name = name;
        this.birthDate = birthDate;
        this.phoneNumber = phoneNumber;
    }

    public User(String serialNumber, String name, String birthDate) {
        this(serialNumber, name, birthDate, "");
    }

    public User(String name, String birthDate) {
        this("", name, birthDate);
    }
}
