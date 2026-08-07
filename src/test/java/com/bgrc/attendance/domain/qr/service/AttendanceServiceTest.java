package com.bgrc.attendance.domain.qr.service;

import com.bgrc.attendance.domain.qr.config.AttendanceLogConfig;
import com.bgrc.attendance.global.common.CustomException;
import com.bgrc.attendance.global.common.ResponseCode;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Date;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AttendanceServiceTest {
    @TempDir
    Path tempDirectory;

    @Test
    void usesMemoryForDuplicateCheckAndWritesExcelAndConfirmationLog() throws Exception {
        LocalDate today = LocalDate.now();
        Path workbookPath = createWorkbook(today);

        AttendanceLogConfig config = mock(AttendanceLogConfig.class);
        when(config.getExcelPath()).thenReturn(workbookPath.toString());
        when(config.getSheetNames()).thenReturn("내역1,내역2");

        AttendanceLogExcelService excelService = new AttendanceLogExcelService(config);
        AttendanceService attendanceService = new AttendanceService(excelService);
        setField(attendanceService, "attendanceDir", tempDirectory.toString());
        attendanceService.init();

        assertThat(attendanceService.isAttended("홍길동", "1990-01-01")).isFalse();

        attendanceService.createLog("홍길동", "1990-01-01");

        assertThat(attendanceService.isAttended("홍길동", "1990-01-01")).isTrue();
        assertThatThrownBy(() -> attendanceService.createLog("홍길동", "1990-01-01"))
                .isInstanceOfSatisfying(CustomException.class,
                        exception -> assertThat(exception.getStatus()).isEqualTo(ResponseCode.ALREADY_CHECKED_IN));

        try (XSSFWorkbook workbook = new XSSFWorkbook(Files.newInputStream(workbookPath))) {
            assertThat(workbook.getSheet("내역1").getRow(2).getCell(4).getStringCellValue())
                    .isEqualTo("o");
        }

        Path confirmationLog = tempDirectory.resolve(
                "무료급식 일일 식사내역_%s.txt".formatted(today.format(DateTimeFormatter.ofPattern("yyyy.MM.dd"))));
        List<String> logLines = Files.readAllLines(confirmationLog);
        assertThat(logLines).containsExactly(
                "[출석 인원 : 1]",
                "홍길동/1990-01-01/%s".formatted(logLines.get(1).substring(logLines.get(1).lastIndexOf('/') + 1)));
    }

    private Path createWorkbook(LocalDate date) throws Exception {
        Path workbookPath = tempDirectory.resolve("attendance.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             OutputStream outputStream = Files.newOutputStream(workbookPath)) {
            CellStyle dateStyle = workbook.createCellStyle();
            dateStyle.setDataFormat(workbook.getCreationHelper().createDataFormat().getFormat("m/d"));

            Sheet sheet = workbook.createSheet("내역1");
            Row header = sheet.createRow(1);
            header.createCell(1).setCellValue("연번");
            header.createCell(2).setCellValue("성명");
            header.createCell(4).setCellValue(Date.valueOf(date));
            header.getCell(4).setCellStyle(dateStyle);

            Row data = sheet.createRow(2);
            data.createCell(1).setCellValue(1);
            data.createCell(2).setCellValue("홍길동");
            data.createCell(4);
            workbook.createSheet("내역2");
            workbook.write(outputStream);
        }
        return workbookPath;
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
