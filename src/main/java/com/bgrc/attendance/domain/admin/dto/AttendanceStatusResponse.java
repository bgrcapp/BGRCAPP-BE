package com.bgrc.attendance.domain.admin.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AttendanceStatusResponse {
    private String date;
    private Integer totalCount;
    private Integer checkedCount;
    private Integer uncheckedCount;
    private List<AttendancePersonResponse> people;
}
