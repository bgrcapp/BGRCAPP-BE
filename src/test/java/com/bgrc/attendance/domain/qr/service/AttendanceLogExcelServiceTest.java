package com.bgrc.attendance.domain.qr.service;

import com.bgrc.attendance.domain.qr.config.AttendanceLogConfig;
import com.bgrc.attendance.global.util.RuntimeDataPathResolver;
import org.apache.poi.ss.usermodel.CellStyle;
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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AttendanceLogExcelServiceTest {
    @TempDir
    Path tempDirectory;

    @Test
    void recordsAttendanceInTheMatchingSheetAndDateColumn() throws Exception {
        LocalDate date = LocalDate.of(2026, 8, 7);
        Path workbookPath = createWorkbook(date);

        AttendanceLogConfig config = monthlyLedgerConfig("내역1,내역2");

        AttendanceLogExcelService service = new AttendanceLogExcelService(config, new RuntimeDataPathResolver());
        service.initialize();

        AttendanceLogExcelService.MarkResult result = service.markAttendance("김철수", date);

        assertThat(result.status()).isEqualTo(AttendanceLogExcelService.MarkStatus.RECORDED);
        assertThat(result.target().key()).isEqualTo("내역2:2");
        assertThat(service.loadTodayMarkedKeys(date)).containsExactly("내역2:2");

        try (XSSFWorkbook workbook = new XSSFWorkbook(Files.newInputStream(workbookPath))) {
            assertThat(workbook.getSheet("내역2").getRow(2).getCell(4).getStringCellValue())
                    .isEqualTo("o");
        }
    }

    @Test
    void returnsAlreadyMarkedWhenTheDateCellIsAlreadyMarked() throws Exception {
        LocalDate date = LocalDate.of(2026, 8, 7);
        Path workbookPath = createWorkbook(date);

        try (XSSFWorkbook workbook = new XSSFWorkbook(Files.newInputStream(workbookPath))) {
            workbook.getSheet("내역1").getRow(2).getCell(4).setCellValue("o");
            try (OutputStream outputStream = Files.newOutputStream(workbookPath)) {
                workbook.write(outputStream);
            }
        }

        AttendanceLogConfig config = monthlyLedgerConfig("내역1,내역2");

        AttendanceLogExcelService service = new AttendanceLogExcelService(config, new RuntimeDataPathResolver());
        service.initialize();

        AttendanceLogExcelService.MarkResult result = service.markAttendance("홍길동", date);

        assertThat(result.status()).isEqualTo(AttendanceLogExcelService.MarkStatus.ALREADY_MARKED);
        assertThat(service.loadTodayMarkedKeys(date)).containsExactly("내역1:1");
    }

    @Test
    void findsTheDateColumnWhenTheLedgerHeaderUsesDisplayedText() throws Exception {
        LocalDate date = LocalDate.of(2026, 8, 10);
        Path workbookPath = monthlyWorkbookPath(date);
        Files.createDirectories(workbookPath.getParent());
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             OutputStream outputStream = Files.newOutputStream(workbookPath)) {
            Sheet sheet = workbook.createSheet("내역1");
            Row header = sheet.createRow(1);
            header.createCell(1).setCellValue("연번");
            header.createCell(2).setCellValue("성명");
            header.createCell(4).setCellValue("8/10");

            Row data = sheet.createRow(2);
            data.createCell(1).setCellValue(1);
            data.createCell(2).setCellValue("홍길동");
            data.createCell(4);
            workbook.write(outputStream);
        }

        AttendanceLogConfig config = monthlyLedgerConfig("내역1");
        AttendanceLogExcelService service = new AttendanceLogExcelService(config, new RuntimeDataPathResolver());
        service.initialize();

        assertThat(service.markAttendance("홍길동", date).status())
                .isEqualTo(AttendanceLogExcelService.MarkStatus.RECORDED);
    }

    private Path createWorkbook(LocalDate date) throws Exception {
        Path workbookPath = monthlyWorkbookPath(date);
        Files.createDirectories(workbookPath.getParent());
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             OutputStream outputStream = Files.newOutputStream(workbookPath)) {
            CellStyle dateStyle = workbook.createCellStyle();
            dateStyle.setDataFormat(workbook.getCreationHelper().createDataFormat().getFormat("m/d"));

            createSheet(workbook, "내역1", 1, "홍길동", date, dateStyle);
            createSheet(workbook, "내역2", 2, "김철수", date, dateStyle);
            workbook.write(outputStream);
        }
        return workbookPath;
    }

    private AttendanceLogConfig monthlyLedgerConfig(String sheetNames) {
        AttendanceLogConfig config = mock(AttendanceLogConfig.class);
        when(config.getMonthlyDir()).thenReturn(tempDirectory.resolve("monthly").toString());
        when(config.getLogDir()).thenReturn(tempDirectory.toString());
        when(config.getSheetNames()).thenReturn(sheetNames);
        return config;
    }

    private Path monthlyWorkbookPath(LocalDate date) {
        return tempDirectory.resolve("monthly")
                .resolve("무료급식 일일 식사내역_%02d.%d_일지.xlsx"
                        .formatted(date.getYear() % 100, date.getMonthValue()));
    }

    private void createSheet(XSSFWorkbook workbook,
                             String sheetName,
                             int serialNumber,
                             String name,
                             LocalDate date,
                             CellStyle dateStyle) {
        Sheet sheet = workbook.createSheet(sheetName);

        Row header = sheet.createRow(1);
        header.createCell(1).setCellValue("연번");
        header.createCell(2).setCellValue("성명");
        header.createCell(4).setCellValue(Date.valueOf(date));
        header.getCell(4).setCellStyle(dateStyle);

        Row data = sheet.createRow(2);
        data.createCell(1).setCellValue(serialNumber);
        data.createCell(2).setCellValue(name);
        data.createCell(4);
    }
}
