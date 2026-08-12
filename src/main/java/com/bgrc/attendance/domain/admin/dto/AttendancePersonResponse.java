package com.bgrc.attendance.domain.admin.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AttendancePersonResponse {
    private String serialNumber;
    private String name;
    private Boolean attended;
}
