package com.bgrc.attendance.domain.admin.dto;

import java.util.List;

/** 월별 출석 일지에 기록된 출석 표시를 집계한 관리자용 통계 응답이다. */
public record AttendanceStatisticsResponse(
        int totalMealCount,
        int uniqueUserCount,
        int sourceFileCount,
        int latestMonthMealCount,
        String latestMonth,
        List<MonthlyAttendanceStatistics> monthlyStatistics,
        List<PersonAttendanceStatistics> people
) {
    public record MonthlyAttendanceStatistics(
            String month,
            int mealCount,
            int cumulativeMealCount,
            int uniqueUserCount,
            int attendanceDayCount,
            double averageDailyCount
    ) {
    }

    public record PersonAttendanceStatistics(
            String serialNumber,
            String name,
            int visitCount,
            String lastAttendanceDate
    ) {
    }
}
