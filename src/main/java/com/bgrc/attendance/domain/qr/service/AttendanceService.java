package com.bgrc.attendance.domain.qr.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import com.bgrc.attendance.global.common.CustomException;
import com.bgrc.attendance.global.common.ResponseCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor // final 필드만 주입
@Slf4j // 로깅 사용
public class AttendanceService {
    @Value("${attendance.log.dir}")
    private String attendanceDir;

    /**
     * 출석 로그 디렉토리 생성
     */
    @PostConstruct // 빈 생성 직후 자동 실행
    public void init(){
        Path dirPath = Path.of(attendanceDir);
        if (!Files.exists(dirPath)){
            try {
                Files.createDirectories(dirPath);
                log.info("출석 디렉토리 생성: {}", dirPath.toAbsolutePath());
            } catch (IOException e) {
                log.error("출석 디렉토리 생성 실패: {}", e.getMessage());
            }
        }
    }

    /**
     * 오늘 날짜의 로그 파일 경로를 반환합니다. <br>
     * 파일명도 포함해서 반환합니다. <br>
     * (ex : C:/Users/무료급식 일일 식사내역_2026-01-30.txt)
     * @return
     */
    private Path getLogPath(){
        String date2str = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy.MM.dd"));
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
    public boolean isAttended(String name, String birthDate) {
        try {
            Path logPath = getLogPath();
            // 파일이 없으면 첫 출석
            if (!Files.exists(logPath)) return false;

            // 이름과 생년월일이 모두 포함되어 있으면 false 반환
            List<String> content = Files.readAllLines(logPath);
            String pattern = name + "/" + birthDate;

            for (String line : content){
                if(line.startsWith(pattern)) return true;
            }
        } catch (IOException e) {
            return false;
        }
        return false;
    }

    /**
     * 출석 로그 파일을 생성하는 로직입니다. <br>
     * 실제로는 프론트에서 스캔 후 3~5초 동안 새로운 스캔을 할 수 없지만,
     * 혹시 모를 상황을 대비해서 {@code synchronized}를 추가하여 lock 영역으로 할당하였습니다.
     * @param name
     * @param birthDate
     */
    public synchronized void createLog(String name, String birthDate){
        try {
            Path logPath = getLogPath();
            log.debug("출석 파일 경로: {}", logPath.toAbsolutePath());

            // 출석 파일 읽기
            List<String> content;
            if (Files.exists(logPath)) {
                content = Files.readAllLines(logPath); // 파일 있으면 읽기
            }
            else {
                content = new ArrayList<>(); // 초기화
            }
            if (!content.isEmpty()) content.remove(0);

            // 마지막 줄에 "이름/생년월일/시:분:초" 추가
            String lastRecord = String.format("%s/%s/%s",
                                            name,
                                            birthDate,
                                            LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")));
            content.add(lastRecord);
            int count = content.size();
            String attendCount = String.format("[출석 인원 : %d]", count);
            content.add(0, attendCount);

            // 출석 파일 덮어쓰기
            Files.write(logPath, content); // 자동으로 개행 문자 붙음
        } catch (IOException e) {
            throw new CustomException(ResponseCode.FILE_WRITE_FAILED);
        }
    }
}
