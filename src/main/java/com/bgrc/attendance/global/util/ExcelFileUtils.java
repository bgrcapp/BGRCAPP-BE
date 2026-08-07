package com.bgrc.attendance.global.util;

import com.bgrc.attendance.domain.user.config.ExcelUploadConfig;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.stream.Stream;

@Component
@RequiredArgsConstructor
public class ExcelFileUtils {
    private final ExcelUploadConfig excelUploadConfig;

    public void ensureDirectory(Path dirPath) throws IOException {
        if(!Files.exists(dirPath)) {
            Files.createDirectories(dirPath);
        }
    }
    public void ensureDirectory(String dir) throws IOException {
        Path dirPath = Path.of(dir);
        if(!Files.exists(dirPath)) {
            Files.createDirectories(dirPath);
        }
    }
    public boolean isExcelExists(Path dirPath) throws IOException {
        try (Stream<Path> stream = Files.list(dirPath)) {
            return stream.anyMatch(
                    path -> path.toString().endsWith(".xlsx") || path.toString().endsWith(".xls"));
        }
    }
    public boolean isExcelExists(String dir) throws IOException {
        try (Stream<Path> stream = Files.list(Path.of(dir))) {
            return stream.anyMatch(
                    path -> path.toString().endsWith(".xlsx") || path.toString().endsWith(".xls"));
        }
    }

    public String getExcelPath() throws IOException {
        Path dirPath = Path.of(excelUploadConfig.getUploadDir());
        try (Stream<Path> stream = Files.list(dirPath)) { // Stream으로 AutoClose
            return stream
                    .filter(path -> path.toString().endsWith(".xlsx") || path.toString().endsWith(".xls"))
                    .findFirst()
                    .map(Path::toString) // == (path -> path.toString())
                    .orElse(null);
        }
    }

    public File getExcelFile() throws IOException {
        String filePath = getExcelPath();
        if (filePath == null) return null;

        return new File(filePath);
    }

    public File getExcelFile(String dirPath) throws IOException {
        boolean isExists = isExcelExists(dirPath);
        if(!isExists) return null;

        return new File(dirPath);
    }

    public String formatBirthDate(Cell cell){
        if (cell != null
                && cell.getCellType() == CellType.NUMERIC
                && DateUtil.isCellDateFormatted(cell)) {
            // 셀 서식이 날짜인 경우 YYYY-MM-DD로 변환
            // 2026-02-03 == 46054.0
            // 46054.0를 getDateCellValue로 Date 객체로 변환
            return cell.getDateCellValue().toInstant() // 에포크 시간으로 변환
                    .atZone(ZoneId.systemDefault()) // 현 운영체제의 시간대
                    .toLocalDate() // 연, 월, 일
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        } else {
            // 셀 서식이 날짜가 아니라 텍스트인 경우
            String val = cell.toString().trim();
            String normalized = val.replace(".", "-").replace("/", "-");

            // yyyy-MM-dd, yyyy/M/d, yyyy.M.d 등 구분자가 있는 날짜
            try {
                return LocalDate.parse(normalized, DateTimeFormatter.ofPattern("uuuu-M-d"))
                        .format(DateTimeFormatter.ISO_LOCAL_DATE);
            } catch (DateTimeParseException ignored) {
                // 아래의 yyyyMMdd 형식을 시도합니다.
            }

            // YYYYMMDD -> YYYY-MM-DD (8자리 숫자인 경우)
            if (val.matches("\\d{8}")) {
                return String.format("%s-%s-%s", val.substring(0, 4), val.substring(4, 6), val.substring(6, 8));
            }

            return normalized;
        }
    }
}
