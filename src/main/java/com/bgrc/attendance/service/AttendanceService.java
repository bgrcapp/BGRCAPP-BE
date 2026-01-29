package com.bgrc.attendance.service;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Service
public class AttendanceService {
    private static final String ATTENDANCE_DIR = "data/attendance"; // 출석 로그 파일 위치

    @PostConstruct // 빈 생성 직후 자동 실행
    public void init(){
        Path dirPath = Path.of(ATTENDANCE_DIR);
        if (!Files.exists(dirPath)){
            try {
                Files.createDirectories(dirPath);
            } catch (IOException e) {
                System.err.println("생성 실패: " + e.getMessage());
            }
        }
    }

    public boolean isAttended(String name, String birthDate) throws IOException {
        String date2str = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy.MM.dd"));
        String logFile = "무료급식 일일 식사내역_%s.txt".formatted(date2str);

        if (!Files.exists(Path.of(ATTENDANCE_DIR,"/",logFile))) return false;

        String content = Files.readString(Path.of(ATTENDANCE_DIR,"/",logFile));
        // 이름과 생년월일이 모두 포함되어 있으면 이미 출석함. 로직 추가 필요
    }

}
