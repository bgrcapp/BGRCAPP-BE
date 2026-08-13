package com.bgrc.attendance.domain.admin.service;

import com.bgrc.attendance.domain.qr.config.AttendanceLogConfig;
import com.bgrc.attendance.global.util.RuntimeDataPathResolver;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 배포 JAR에 포함된 2026년 1~7월 출석 일지를 실행 폴더의 data/attendance로 한 번 복원한다.
 * 운영 중 생성하거나 수정한 파일은 파일명이 같은 달이라도 절대 덮어쓰지 않는다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BundledHistoricalAttendanceLedgerService {
    private static final int HISTORY_YEAR = 2026;
    // macOS는 한글 파일명을 자모 분리 형태로 저장할 수 있으므로, 한글 접미사는 비교하지 않는다.
    private static final Pattern LEDGER_FILE_NAME = Pattern.compile(
            ".*_(\\d{2})\\.(\\d{1,2})_.*\\.xlsx$", Pattern.CASE_INSENSITIVE);

    private final AttendanceLogConfig attendanceLogConfig;
    private final RuntimeDataPathResolver runtimeDataPathResolver;

    @PostConstruct
    public void restoreMissingHistoricalLedgers() {
        Path ledgerDirectory = configuredLedgerDirectory();
        try {
            Files.createDirectories(ledgerDirectory);
        } catch (IOException e) {
            log.warn("기존 출석 일지 복원 폴더를 만들지 못했습니다: {}", ledgerDirectory, e);
            return;
        }

        for (int month = 1; month <= 7; month++) {
            YearMonth yearMonth = YearMonth.of(HISTORY_YEAR, month);
            if (hasLedgerForMonth(ledgerDirectory, yearMonth)) continue;

            String bundledPath = "historical-attendance/%d-%02d.xlsx".formatted(HISTORY_YEAR, month);
            ClassPathResource resource = new ClassPathResource(bundledPath);
            if (!resource.exists()) {
                log.warn("배포 JAR에 기존 출석 일지가 없습니다: {}", bundledPath);
                continue;
            }

            Path target = ledgerDirectory.resolve(ledgerFileName(yearMonth));
            try (InputStream inputStream = resource.getInputStream()) {
                Files.copy(inputStream, target);
                log.info("기존 {}월 출석 일지를 복원했습니다: {}", month, target);
            } catch (IOException e) {
                log.warn("기존 {}월 출석 일지 복원에 실패했습니다: {}", month, target, e);
            }
        }
    }

    private boolean hasLedgerForMonth(Path directory, YearMonth expectedMonth) {
        try (Stream<Path> paths = Files.list(directory)) {
            return paths
                    .filter(Files::isRegularFile)
                    .map(path -> yearMonthFromFileName(path.getFileName().toString()))
                    .flatMap(Optional::stream)
                    .anyMatch(expectedMonth::equals);
        } catch (IOException e) {
            log.warn("기존 출석 일지 목록을 읽지 못했습니다: {}", directory, e);
            return false;
        }
    }

    private Optional<YearMonth> yearMonthFromFileName(String fileName) {
        Matcher matcher = LEDGER_FILE_NAME.matcher(fileName);
        if (!matcher.matches()) return Optional.empty();
        try {
            return Optional.of(YearMonth.of(
                    2000 + Integer.parseInt(matcher.group(1)),
                    Integer.parseInt(matcher.group(2))));
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private Path configuredLedgerDirectory() {
        String directory = attendanceLogConfig.getMonthlyDir();
        return directory == null || directory.isBlank()
                ? runtimeDataPathResolver.resolve(attendanceLogConfig.getLogDir())
                : runtimeDataPathResolver.resolve(directory);
    }

    private String ledgerFileName(YearMonth month) {
        return "무료급식 일일 식사내역_%02d.%d_일지.xlsx"
                .formatted(month.getYear() % 100, month.getMonthValue());
    }
}
