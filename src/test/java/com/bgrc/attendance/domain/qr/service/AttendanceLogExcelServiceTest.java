package com.bgrc.attendance.domain.qr.service;

import com.bgrc.attendance.domain.qr.config.AttendanceLogConfig;
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

        AttendanceLogConfig config = mock(AttendanceLogConfig.class);
        when(config.getExcelPath()).thenReturn(workbookPath.toString());
        when(config.getSheetNames()).thenReturn("내역1,내역2");

        AttendanceLogExcelService service = new AttendanceLogExcelService(config);
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

        AttendanceLogConfig config = mock(AttendanceLogConfig.class);
        when(config.getExcelPath()).thenReturn(workbookPath.toString());
        when(config.getSheetNames()).thenReturn("내역1,내역2");

        AttendanceLogExcelService service = new AttendanceLogExcelService(config);
        service.initialize();

        AttendanceLogExcelService.MarkResult result = service.markAttendance("홍길동", date);

        assertThat(result.status()).isEqualTo(AttendanceLogExcelService.MarkStatus.ALREADY_MARKED);
        assertThat(service.loadTodayMarkedKeys(date)).containsExactly("내역1:1");
    }

    private Path createWorkbook(LocalDate date) throws Exception {
        Path workbookPath = tempDirectory.resolve("attendance.xlsx");
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
