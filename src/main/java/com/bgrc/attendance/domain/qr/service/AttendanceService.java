package com.bgrc.attendance.domain.qr.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.bgrc.attendance.global.common.CustomException;
import com.bgrc.attendance.global.common.ResponseCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor // final 필드만 주입
@Slf4j // 로깅 사용
public class AttendanceService {
    private final AttendanceLogExcelService attendanceLogExcelService;

    @Value("${attendance.log.dir}")
    private String attendanceDir;

    private final Set<String> attendedToday = new HashSet<>();
    private final List<String> confirmationRecords = new ArrayList<>();
    private LocalDate loadedDate;

    /**
     * 출석 로그 디렉토리 생성
     */
    @PostConstruct // 빈 생성 직후 자동 실행
    public synchronized void init(){
        Path dirPath = Path.of(attendanceDir);
        if (!Files.exists(dirPath)){
            try {
                Files.createDirectories(dirPath);
                log.info("출석 디렉토리 생성: {}", dirPath.toAbsolutePath());
            } catch (IOException e) {
                log.error("출석 디렉토리 생성 실패: {}", e.getMessage());
            }
        }

        loadTodayState();
    }

    /**
     * 오늘 날짜의 로그 파일 경로를 반환합니다. <br>
     * 파일명도 포함해서 반환합니다. <br>
     * (ex : C:/Users/무료급식 일일 식사내역_2026-01-30.txt)
     * @return
     */
    private Path getLogPath(LocalDate date){
        String date2str = date.format(DateTimeFormatter.ofPattern("yyyy.MM.dd"));
        String logFile = "무료급식 일일 식사내역_%s.txt".formatted(date2str);
        Path logFilePath = Path.of(attendanceDir, logFile);

        return logFilePath;
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
        return attendanceLogExcelService.findUniqueTarget(name)
                .map(target -> attendedToday.contains(target.key()))
                .orElse(false);
    }

    /**
     * 출석 로그 파일을 생성하는 로직입니다. <br>
     * 실제로는 프론트에서 스캔 후 3~5초 동안 새로운 스캔을 할 수 없지만,
     * 혹시 모를 상황을 대비해서 {@code synchronized}를 추가하여 lock 영역으로 할당하였습니다.
     * @param name
     * @param birthDate
     */
    public synchronized void createLog(String name, String birthDate){
        ensureTodayState();

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
            case RECORDED -> {
                attendedToday.add(result.target().key());
                appendConfirmationLog(name, birthDate);
            }
        }
    }

    private void ensureTodayState() {
        LocalDate today = LocalDate.now();
        if (!today.equals(loadedDate)) loadTodayState();
    }

    private void loadTodayState() {
        loadedDate = LocalDate.now();
        attendedToday.clear();
        confirmationRecords.clear();

        attendanceLogExcelService.initialize();
        attendedToday.addAll(attendanceLogExcelService.loadTodayMarkedKeys(loadedDate));
        loadConfirmationLog();
    }

    /**
     * 서버 시작 시에만 확인용 txt 로그를 읽어 메모리 상태를 보강합니다.
     * 정상적인 QR 처리 중에는 txt 파일을 읽지 않습니다.
     */
    private void loadConfirmationLog() {
        Path logPath = getLogPath(loadedDate);
        if (!Files.exists(logPath)) return;

        try {
            List<String> content = Files.readAllLines(logPath);
            for (int i = 1; i < content.size(); i++) {
                String record = content.get(i).trim();
                if (record.isBlank()) continue;
                confirmationRecords.add(record);

                String[] data = record.split("/", 3);
                if (data.length < 2) continue;
                attendanceLogExcelService.findUniqueTarget(data[0])
                        .ifPresent(target -> attendedToday.add(target.key()));
            }
        } catch (IOException e) {
            log.warn("확인용 출석 로그 복구 실패: {}", logPath, e);
        }
    }

    private void appendConfirmationLog(String name, String birthDate) {
        String record = String.format("%s/%s/%s",
                name,
                birthDate,
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")));
        confirmationRecords.add(record);
        writeConfirmationLog(loadedDate);
    }

    @PreDestroy
    public synchronized void shutdown() {
        if (loadedDate != null) writeConfirmationLog(loadedDate);
    }

    private void writeConfirmationLog(LocalDate date) {
        List<String> output = new ArrayList<>();
        output.add(String.format("[출석 인원 : %d]", attendedToday.size()));
        output.addAll(confirmationRecords);

        try {
            Files.write(getLogPath(date), output);
        } catch (IOException e) {
            // txt는 확인용 보조 로그이므로 Excel 출석 저장 결과를 실패로 되돌리지 않습니다.
            log.warn("확인용 출석 로그 저장 실패: {}", getLogPath(date), e);
        }
    }
}
