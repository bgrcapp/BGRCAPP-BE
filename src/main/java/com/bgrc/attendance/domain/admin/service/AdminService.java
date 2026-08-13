package com.bgrc.attendance.domain.admin.service;

import com.bgrc.attendance.domain.admin.dto.AdminResponse;
import com.bgrc.attendance.domain.admin.dto.AttendancePersonResponse;
import com.bgrc.attendance.domain.admin.dto.AttendanceStatusResponse;
import com.bgrc.attendance.domain.admin.dto.AttendanceStatisticsResponse;
import com.bgrc.attendance.domain.qr.service.AttendanceLogExcelService;
import com.bgrc.attendance.domain.qr.service.AttendanceService;
import com.bgrc.attendance.domain.qr.service.MonthlyAttendanceLedgerService;
import com.bgrc.attendance.domain.user.model.User;
import com.bgrc.attendance.domain.user.service.UserService;
import com.bgrc.attendance.global.common.CustomException;
import com.bgrc.attendance.global.common.ResponseCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class AdminService {
    private final UserService userService;
    private final AttendanceLogExcelService attendanceLogExcelService;
    private final MonthlyAttendanceLedgerService monthlyAttendanceLedgerService;
    private final AttendanceService attendanceService;
    private final AttendanceStatisticsService attendanceStatisticsService;

    /** 현재 활성화된 출석 대상 명단의 상태를 반환한다. */
    public synchronized AdminResponse getConfig(){
        return AdminResponse.builder()
                .userCount(userService.getUserCount())
                .fileExists(userService.hasRosterFile())
                .rosterFileName(userService.getActiveRosterFileName())
                .build();
    }

    /**
     * 표시 대상은 관리자가 업로드한 명단이고, 출석 여부는 생성된 월별 출석 일지의 선택 날짜
     * 칸에 기록된 `o`와 매칭한다.
     */
    public synchronized AttendanceStatusResponse getAttendance(LocalDate date) {
        List<User> users = userService.getUsers();
        monthlyAttendanceLedgerService.ensureLedger(date, users);
        attendanceLogExcelService.ensureInitialized(date);
        Set<String> markedKeys = attendanceLogExcelService.loadMarkedKeys(date);
        List<AttendancePersonResponse> people = users.stream()
                .map(user -> toAttendancePerson(user, markedKeys))
                .toList();
        int checkedCount = (int) people.stream().filter(AttendancePersonResponse::getAttended).count();

        return AttendanceStatusResponse.builder()
                .date(date.format(DateTimeFormatter.ISO_LOCAL_DATE))
                .totalCount(people.size())
                .checkedCount(checkedCount)
                .uncheckedCount(people.size() - checkedCount)
                .people(people)
                .build();
    }

    private AttendancePersonResponse toAttendancePerson(User user, Set<String> markedKeys) {
        boolean attended = attendanceLogExcelService.findTarget(user)
                .map(target -> markedKeys.contains(target.key()))
                .orElse(false);
        return AttendancePersonResponse.builder()
                .serialNumber(user.getSerialNumber())
                .name(user.getName())
                .attended(attended)
                .build();
    }

    /** 업로드 파일을 검증한 뒤 활성 출석 대상 명단으로 교체한다. */
    public synchronized void uploadRoster(MultipartFile file){
        UserService.RosterReplacement replacement = userService.replaceRosterWithRollback(file);
        try {
            // reloadCurrentDate()가 새 명단으로 월별 일지를 재생성하고 기존 출석(o)을 이어받는다.
            attendanceService.reloadCurrentDate();
            replacement.complete();
        } catch (RuntimeException e) {
            replacement.rollback();
            // 롤백된 기존 명단 기준으로 일지도 즉시 되돌린다. 원래 오류를 사용자에게 전달한다.
            try {
                attendanceService.reloadCurrentDate();
            } catch (RuntimeException rollbackFailure) {
                log.error("명단 업로드 롤백 후 월별 일지 재동기화 실패", rollbackFailure);
            }
            throw e;
        }
    }

    /** 관리자가 선택한 날짜의 출석 여부를 직접 반대로 변경한다. */
    public synchronized AttendanceStatusResponse toggleAttendance(LocalDate date, String serialNumber) {
        User user = userService.getUsers().stream()
                .filter(candidate -> candidate.getSerialNumber().equals(serialNumber))
                .findFirst()
                .orElseThrow(() -> new CustomException(ResponseCode.INVALID_USER_INFO));
        attendanceService.toggleAttendance(user, date);
        return getAttendance(date);
    }

    /** 선택 날짜가 포함된 월별 출석 일지 원본을 내려받기 위한 경로를 반환한다. */
    public synchronized Path getAttendanceLedgerPath(LocalDate date) {
        List<User> users = userService.getUsers();
        Path ledgerPath = monthlyAttendanceLedgerService.ensureLedger(date, users);
        if (!Files.isRegularFile(ledgerPath)) {
            throw new CustomException(ResponseCode.ATTENDANCE_LOG_FILE_NOT_FOUND);
        }
        return ledgerPath;
    }

    /** 보관 중인 월별 출석 일지 전체를 기준으로 통계를 반환한다. */
    public synchronized AttendanceStatisticsResponse getAttendanceStatistics() {
        return attendanceStatisticsService.getStatistics();
    }
}
