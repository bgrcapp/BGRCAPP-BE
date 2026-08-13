package com.bgrc.attendance.domain.qr.service;

import com.bgrc.attendance.domain.qr.config.AttendanceLogConfig;
import com.bgrc.attendance.domain.user.service.UserService;
import com.bgrc.attendance.global.util.RuntimeDataPathResolver;
import com.bgrc.attendance.global.common.CustomException;
import com.bgrc.attendance.global.common.ResponseCode;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AttendanceServiceTest {
    @TempDir
    Path tempDirectory;

    @Test
    void usesMemoryForDuplicateCheckAndWritesOnlyTheGeneratedMonthlyLedger() throws Exception {
        LocalDate today = LocalDate.now();
        Path monthlyDirectory = tempDirectory.resolve("monthly");
        Path workbookPath = createWorkbook(monthlyDirectory, today);

        AttendanceLogConfig config = mock(AttendanceLogConfig.class);
        when(config.getMonthlyDir()).thenReturn(monthlyDirectory.toString());
        when(config.getLogDir()).thenReturn(tempDirectory.toString());
        when(config.getSheetNames()).thenReturn("내역1,내역2");

        AttendanceLogExcelService excelService = new AttendanceLogExcelService(config, new RuntimeDataPathResolver());
        MonthlyAttendanceLedgerService monthlyAttendanceLedgerService = mock(MonthlyAttendanceLedgerService.class);
        UserService userService = mock(UserService.class);
        when(userService.getUsers()).thenReturn(List.of());
        AttendanceService attendanceService = new AttendanceService(excelService, monthlyAttendanceLedgerService, userService);
        attendanceService.init();

        verify(monthlyAttendanceLedgerService).ensureLedger(today, List.of());
        verify(monthlyAttendanceLedgerService, never()).synchronizeCurrentMonth(today, List.of());

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

        try (var paths = Files.list(tempDirectory)) {
            assertThat(paths.map(path -> path.getFileName().toString()))
                    .noneMatch(fileName -> fileName.endsWith(".txt"));
        }

        attendanceService.reloadCurrentDate();
        verify(monthlyAttendanceLedgerService).synchronizeCurrentMonth(today, List.of());
    }

    private Path createWorkbook(Path monthlyDirectory, LocalDate date) throws Exception {
        Files.createDirectories(monthlyDirectory);
        Path workbookPath = monthlyDirectory.resolve("무료급식 일일 식사내역_%02d.%d_일지.xlsx"
                .formatted(date.getYear() % 100, date.getMonthValue()));
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
}
