package com.bgrc.attendance.domain.qr.service;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import com.bgrc.attendance.global.common.CustomException;
import com.bgrc.attendance.global.common.ResponseCode;
import com.bgrc.attendance.domain.user.model.User;
import com.bgrc.attendance.domain.user.service.UserService;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class AttendanceService {
    private final AttendanceLogExcelService attendanceLogExcelService;
    private final MonthlyAttendanceLedgerService monthlyAttendanceLedgerService;
    private final UserService userService;

    private final Set<String> attendedToday = new HashSet<>();
    private LocalDate loadedDate;

    /** 서버 시작 시 오늘 생성된 월별 일지에서 출석 상태를 불러온다. */
    @PostConstruct
    public synchronized void init(){
        loadTodayState(false);
    }

    /**
     * 출석 여부 확인 로직입니다. <br>
     * 파일 읽는 과정에서 오류가 발생할 경우 {@code false}를 반환합니다.
     * @param name          이름
     * @param birthDate     생년월일
     * @return
     */
    public synchronized boolean isAttended(String name, String birthDate) {
        ensureTodayState();
        attendanceLogExcelService.ensureInitialized(loadedDate);
        return attendanceLogExcelService.findUniqueTarget(name)
                .map(target -> attendedToday.contains(target.key()))
                .orElse(false);
    }

    /** QR 출석을 오늘의 월별 일지에 기록한다. */
    public synchronized void createLog(String name, String birthDate){
        ensureTodayState();
        attendanceLogExcelService.ensureInitialized(loadedDate);

        Optional<AttendanceLogExcelService.AttendanceTarget> target =
                attendanceLogExcelService.findUniqueTarget(name);
        if (target.isEmpty()) {
            throw new CustomException(ResponseCode.ATTENDANCE_LOG_TARGET_NOT_FOUND);
        }

        if (attendedToday.contains(target.get().key())) {
            throw new CustomException(ResponseCode.ALREADY_CHECKED_IN);
        }

        AttendanceLogExcelService.MarkResult result =
                attendanceLogExcelService.markAttendance(name, loadedDate);
        switch (result.status()) {
            case ALREADY_MARKED -> {
                attendedToday.add(result.target().key());
                throw new CustomException(ResponseCode.ALREADY_CHECKED_IN);
            }
            case TARGET_NOT_FOUND -> throw new CustomException(ResponseCode.ATTENDANCE_LOG_TARGET_NOT_FOUND);
            case DATE_NOT_FOUND -> throw new CustomException(ResponseCode.ATTENDANCE_LOG_DATE_NOT_FOUND);
            case RECORDED -> attendedToday.add(result.target().key());
        }
    }

    /** QR 원본 명단의 연번·이름으로 월별 일지 행을 정확히 찾아 출석을 기록한다. */
    public synchronized void createLog(User user) {
        long startedAt = System.nanoTime();
        try {
            ensureTodayState();
            attendanceLogExcelService.ensureInitialized(loadedDate);

            Optional<AttendanceLogExcelService.AttendanceTarget> target = attendanceLogExcelService.findTarget(user);
            if (target.isEmpty()) {
                throw new CustomException(ResponseCode.ATTENDANCE_LOG_TARGET_NOT_FOUND);
            }
            if (attendedToday.contains(target.get().key())) {
                throw new CustomException(ResponseCode.ALREADY_CHECKED_IN);
            }

            AttendanceLogExcelService.MarkResult result = attendanceLogExcelService.markAttendance(user, loadedDate);
            switch (result.status()) {
                case ALREADY_MARKED -> {
                    attendedToday.add(result.target().key());
                    throw new CustomException(ResponseCode.ALREADY_CHECKED_IN);
                }
                case TARGET_NOT_FOUND -> throw new CustomException(ResponseCode.ATTENDANCE_LOG_TARGET_NOT_FOUND);
                case DATE_NOT_FOUND -> throw new CustomException(ResponseCode.ATTENDANCE_LOG_DATE_NOT_FOUND);
                case RECORDED -> attendedToday.add(result.target().key());
            }
        } finally {
            log.info("QR 출석 일지 처리 시간: {} ms ({})", elapsedMillis(startedAt), user.getName());
        }
    }

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    private void ensureTodayState() {
        LocalDate today = LocalDate.now();
        if (!today.equals(loadedDate)) loadTodayState(false);
    }

    /**
     * 서버 시작·날짜 변경 때는 기존 일지를 그대로 사용한다.
     * 명단 업로드 직후에만 현재 명단에 맞춰 일지를 다시 구성하며, 기존 출석 표시는 이어받는다.
     */
    private void loadTodayState(boolean synchronizeRoster) {
        loadedDate = LocalDate.now();
        attendedToday.clear();

        if (synchronizeRoster) {
            monthlyAttendanceLedgerService.synchronizeCurrentMonth(loadedDate, userService.getUsers());
        } else {
            monthlyAttendanceLedgerService.ensureLedger(loadedDate, userService.getUsers());
        }
        attendanceLogExcelService.initialize(loadedDate);
        attendedToday.addAll(attendanceLogExcelService.loadTodayMarkedKeys(loadedDate));
    }

    /** 명단 교체 직후 현재 월 일지와 메모리 출석 상태를 다시 맞춘다. */
    public synchronized void reloadCurrentDate() {
        loadTodayState(true);
    }

    /** 관리자 화면의 수동 출석/결석 변경도 Excel과 오늘의 메모리 상태에 함께 반영한다. */
    public synchronized boolean toggleAttendance(User user, LocalDate date) {
        if (date.equals(LocalDate.now())) ensureTodayState();

        monthlyAttendanceLedgerService.ensureLedger(date, userService.getUsers());
        attendanceLogExcelService.ensureInitialized(date);
        boolean attended = attendanceLogExcelService.toggleAttendance(user, date);

        if (date.equals(loadedDate)) {
            AttendanceLogExcelService.AttendanceTarget target = attendanceLogExcelService.findTarget(user)
                    .orElseThrow(() -> new CustomException(ResponseCode.ATTENDANCE_LOG_TARGET_NOT_FOUND));
            if (attended) {
                attendedToday.add(target.key());
            } else {
                attendedToday.remove(target.key());
            }
        }
        return attended;
    }

}
