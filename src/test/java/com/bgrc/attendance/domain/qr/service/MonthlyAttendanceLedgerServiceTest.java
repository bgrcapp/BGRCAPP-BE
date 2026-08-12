package com.bgrc.attendance.domain.qr.service;

import com.bgrc.attendance.domain.qr.config.AttendanceLogConfig;
import com.bgrc.attendance.domain.qr.config.HolidayApiConfig;
import com.bgrc.attendance.domain.user.model.User;
import com.bgrc.attendance.global.util.RuntimeDataPathResolver;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MonthlyAttendanceLedgerServiceTest {
    @TempDir
    Path tempDirectory;

    @Test
    void createsMonthlyLedgerFromRosterAndExcludesWeekendsAndKoreanHolidays() throws Exception {
        Path templatePath = createTemplate();
        Path monthlyDirectory = tempDirectory.resolve("monthly");
        AttendanceLogConfig config = mock(AttendanceLogConfig.class);
        when(config.getTemplatePath()).thenReturn(templatePath.toString());
        when(config.getMonthlyDir()).thenReturn(monthlyDirectory.toString());
        when(config.getLogDir()).thenReturn(tempDirectory.toString());
        when(config.getSheetNames()).thenReturn("내역1,내역2");

        HolidayApiConfig holidayApiConfig = mock(HolidayApiConfig.class);
        when(holidayApiConfig.getServiceKey()).thenReturn("");
        when(holidayApiConfig.getCacheDir()).thenReturn(tempDirectory.resolve("holiday-cache").toString());
        MonthlyAttendanceLedgerService service = new MonthlyAttendanceLedgerService(
                config, new KoreanHolidayCalendar(holidayApiConfig), new RuntimeDataPathResolver());
        LocalDate date = LocalDate.of(2026, 8, 10);
        List<User> users = List.of(
                new User("1", "이용자1", "1959-03-27"),
                new User("2", "이용자2", "1960-04-20"));
        // 이전 날짜별 txt 기록은 새 월별 일지에 이관하거나 복구하지 않는다.
        Files.writeString(tempDirectory.resolve("confirmation_2026.08.10.txt"), "이용자1/1959-03-27/12:00:00");
        Path ledgerPath = service.ensureLedger(date, users);

        assertThat(ledgerPath).exists();
        try (XSSFWorkbook workbook = new XSSFWorkbook(Files.newInputStream(ledgerPath))) {
            Sheet firstSheet = workbook.getSheet("내역1");
            Row header = firstSheet.getRow(1);
            assertThat(header.getCell(1).getStringCellValue()).isEqualTo("연번");
            assertThat(header.getCell(2).getStringCellValue()).isEqualTo("성명");
            List<LocalDate> dates = java.util.stream.IntStream.range(4, header.getLastCellNum())
                    .mapToObj(header::getCell)
                    .filter(cell -> cell != null && cell.getCellType() == org.apache.poi.ss.usermodel.CellType.NUMERIC)
                    .map(cell -> DateUtil.getJavaDate(cell.getNumericCellValue()).toInstant()
                            .atZone(java.time.ZoneId.systemDefault()).toLocalDate())
                    .toList();

            assertThat(dates).contains(LocalDate.of(2026, 8, 10));
            assertThat(dates).doesNotContain(LocalDate.of(2026, 8, 15), LocalDate.of(2026, 8, 16), LocalDate.of(2026, 8, 17));
            assertThat(firstSheet.getRow(3).getCell(1).getStringCellValue()).isEqualTo("1");
            assertThat(firstSheet.getRow(3).getCell(2).getStringCellValue()).isEqualTo("이용자1");
            int augustTenthColumn = java.util.stream.IntStream.range(4, header.getLastCellNum())
                    .filter(index -> DateUtil.isCellDateFormatted(header.getCell(index)))
                    .filter(index -> DateUtil.getJavaDate(header.getCell(index).getNumericCellValue()).toInstant()
                            .atZone(java.time.ZoneId.systemDefault()).toLocalDate().equals(date))
                    .findFirst()
                    .orElseThrow();
            assertThat(firstSheet.getRow(3).getCell(augustTenthColumn).getStringCellValue()).isBlank();
            assertThat(firstSheet.getRow(4).getCell(1).getStringCellValue()).isEqualTo("2");
        }
    }

    @Test
    void preservesMarksWhenMovingAwayFromTheLegacyMonthlyDirectory() throws Exception {
        Path templatePath = createTemplate();
        LocalDate date = LocalDate.of(2026, 8, 10);
        List<User> users = List.of(new User("1", "이용자1", "1959-03-27"));

        AttendanceLogConfig legacyConfig = attendanceConfig(templatePath, tempDirectory.resolve("monthly"));
        MonthlyAttendanceLedgerService legacyService = new MonthlyAttendanceLedgerService(
                legacyConfig, holidayCalendar(), new RuntimeDataPathResolver());
        Path legacyLedgerPath = legacyService.ensureLedger(date, users);
        markAttendance(legacyLedgerPath, date);

        AttendanceLogConfig directConfig = attendanceConfig(templatePath, tempDirectory);
        MonthlyAttendanceLedgerService directService = new MonthlyAttendanceLedgerService(
                directConfig, holidayCalendar(), new RuntimeDataPathResolver());
        Path directLedgerPath = directService.synchronizeCurrentMonth(date, users);

        assertThat(directLedgerPath).isEqualTo(tempDirectory.resolve("무료급식 일일 식사내역_26.8_일지.xlsx"));
        assertThat(markedOn(directLedgerPath, date)).isTrue();
        assertThat(legacyLedgerPath).doesNotExist();
        assertThat(tempDirectory.resolve("legacy-backups/monthly-directory")).isDirectory();
    }

    @Test
    void createsASeparateLedgerWhenTheMonthChanges() throws Exception {
        Path templatePath = createTemplate();
        AttendanceLogConfig config = attendanceConfig(templatePath, tempDirectory);
        MonthlyAttendanceLedgerService service = new MonthlyAttendanceLedgerService(
                config, holidayCalendar(), new RuntimeDataPathResolver());
        List<User> users = List.of(new User("1", "이용자1", "1959-03-27"));

        Path august = service.ensureLedger(LocalDate.of(2026, 8, 31), users);
        Path september = service.ensureLedger(LocalDate.of(2026, 9, 1), users);

        assertThat(august).exists();
        assertThat(september).exists();
        assertThat(august).isNotEqualTo(september);
        assertThat(september.getFileName().toString()).isEqualTo("무료급식 일일 식사내역_26.9_일지.xlsx");
    }

    private AttendanceLogConfig attendanceConfig(Path templatePath, Path ledgerDirectory) {
        AttendanceLogConfig config = mock(AttendanceLogConfig.class);
        when(config.getTemplatePath()).thenReturn(templatePath.toString());
        when(config.getMonthlyDir()).thenReturn(ledgerDirectory.toString());
        when(config.getLogDir()).thenReturn(tempDirectory.toString());
        when(config.getSheetNames()).thenReturn("내역1,내역2");
        return config;
    }

    private KoreanHolidayCalendar holidayCalendar() {
        HolidayApiConfig holidayApiConfig = mock(HolidayApiConfig.class);
        when(holidayApiConfig.getServiceKey()).thenReturn("");
        when(holidayApiConfig.getCacheDir()).thenReturn(tempDirectory.resolve("holiday-cache").toString());
        return new KoreanHolidayCalendar(holidayApiConfig);
    }

    private void markAttendance(Path ledgerPath, LocalDate date) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(Files.newInputStream(ledgerPath));
             OutputStream outputStream = Files.newOutputStream(ledgerPath)) {
            Sheet sheet = workbook.getSheet("내역1");
            int dateColumn = dateColumn(sheet, date);
            sheet.getRow(3).getCell(dateColumn).setCellValue("o");
            workbook.write(outputStream);
        }
    }

    private boolean markedOn(Path ledgerPath, LocalDate date) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(Files.newInputStream(ledgerPath))) {
            Sheet sheet = workbook.getSheet("내역1");
            return "o".equals(sheet.getRow(3).getCell(dateColumn(sheet, date)).getStringCellValue());
        }
    }

    private int dateColumn(Sheet sheet, LocalDate date) {
        Row header = sheet.getRow(1);
        return java.util.stream.IntStream.range(4, header.getLastCellNum())
                .filter(index -> DateUtil.isCellDateFormatted(header.getCell(index)))
                .filter(index -> DateUtil.getJavaDate(header.getCell(index).getNumericCellValue()).toInstant()
                        .atZone(java.time.ZoneId.systemDefault()).toLocalDate().equals(date))
                .findFirst()
                .orElseThrow();
    }

    private Path createTemplate() throws Exception {
        Path templatePath = tempDirectory.resolve("template.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             OutputStream outputStream = Files.newOutputStream(templatePath)) {
            CellStyle dateStyle = workbook.createCellStyle();
            dateStyle.setDataFormat(workbook.getCreationHelper().createDataFormat().getFormat("m/d"));
            createSheet(workbook, "내역1", dateStyle);
            createSheet(workbook, "내역2", dateStyle);
            workbook.write(outputStream);
        }
        return templatePath;
    }

    private void createSheet(XSSFWorkbook workbook, String sheetName, CellStyle dateStyle) {
        Sheet sheet = workbook.createSheet(sheetName);
        Row header = sheet.createRow(1);
        header.createCell(0).setCellValue(2026);
        header.createCell(1).setCellValue("연번");
        header.createCell(2).setCellValue("성명");
        Cell dateHeader = header.createCell(4);
        dateHeader.setCellValue(Date.valueOf("2026-08-03"));
        dateHeader.setCellStyle(dateStyle);

        Row weekday = sheet.createRow(2);
        weekday.createCell(4).setCellValue("월");
        Row prototype = sheet.createRow(3);
        prototype.createCell(1);
        prototype.createCell(2);
        prototype.createCell(4);
    }
}
