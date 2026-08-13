package com.bgrc.attendance.domain.admin.service;

import com.bgrc.attendance.domain.admin.dto.AttendanceStatisticsResponse;
import com.bgrc.attendance.domain.qr.config.AttendanceLogConfig;
import com.bgrc.attendance.domain.user.model.User;
import com.bgrc.attendance.domain.user.service.UserService;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AttendanceStatisticsServiceTest {
    @TempDir
    Path tempDirectory;

    @Test
    void usesTheExistingDailyCountFormulaRangeForHistoricalLedgers() throws Exception {
        createWorkbook(YearMonthFile.JANUARY, LocalDate.of(2026, 1, 5), true,
                new Person("1", "가나다", true),
                new Person("2", "라마바", true),
                new Person("3", "사아자", true));
        createWorkbook(YearMonthFile.FEBRUARY, LocalDate.of(2026, 2, 2), false,
                new Person("2", "라마바", true));

        UserService userService = mock(UserService.class);
        when(userService.getUsers()).thenReturn(List.of());
        AttendanceStatisticsService service = new AttendanceStatisticsService(
                config(), new RuntimeDataPathResolver(), userService);
        AttendanceStatisticsResponse result = service.getStatistics();

        // 1월 3명 중 세 번째 행은 기존 COUNTIF(E4:E5, "O") 범위 밖이라 공식 누계에서 제외된다.
        assertThat(result.totalMealCount()).isEqualTo(3);
        assertThat(result.uniqueUserCount()).isEqualTo(2);
        assertThat(result.sourceFileCount()).isEqualTo(2);
        assertThat(result.monthlyStatistics())
                .extracting(AttendanceStatisticsResponse.MonthlyAttendanceStatistics::mealCount)
                .containsExactly(2, 1);
        assertThat(result.monthlyStatistics())
                .extracting(AttendanceStatisticsResponse.MonthlyAttendanceStatistics::cumulativeMealCount)
                .containsExactly(2, 3);
        assertThat(result.people())
                .extracting(AttendanceStatisticsResponse.PersonAttendanceStatistics::name,
                        AttendanceStatisticsResponse.PersonAttendanceStatistics::visitCount)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("가나다", 1),
                        org.assertj.core.groups.Tuple.tuple("라마바", 2));
    }

    @Test
    void groupsChangedSerialNumbersByTheRosterNameAndBirthDatePrimaryKey() throws Exception {
        createWorkbook(YearMonthFile.JANUARY, LocalDate.of(2026, 1, 5), false,
                new Person("45", "한은정", "010-4382-2722", true));
        createWorkbook(YearMonthFile.FEBRUARY, LocalDate.of(2026, 2, 2), false,
                new Person("40", "한은정", "01043822722", true));

        UserService userService = mock(UserService.class);
        when(userService.getUsers()).thenReturn(List.of(
                new User("40", "한은정", "1950-01-01", "010-4382-2722")));
        AttendanceStatisticsService service = new AttendanceStatisticsService(
                config(), new RuntimeDataPathResolver(), userService);

        AttendanceStatisticsResponse result = service.getStatistics();

        assertThat(result.uniqueUserCount()).isEqualTo(1);
        assertThat(result.people()).singleElement().satisfies(person -> {
            assertThat(person.serialNumber()).isEqualTo("40");
            assertThat(person.name()).isEqualTo("한은정");
            assertThat(person.visitCount()).isEqualTo(2);
            assertThat(person.lastAttendanceDate()).isEqualTo("2026-02-02");
        });
    }

    private AttendanceLogConfig config() {
        AttendanceLogConfig config = mock(AttendanceLogConfig.class);
        when(config.getMonthlyDir()).thenReturn(tempDirectory.toString());
        when(config.getLogDir()).thenReturn(tempDirectory.toString());
        when(config.getSheetNames()).thenReturn("내역1,내역2");
        return config;
    }

    private void createWorkbook(YearMonthFile file,
                                LocalDate date,
                                boolean hasDailyCountFormula,
                                Person... people) throws Exception {
        Path workbookPath = tempDirectory.resolve(file.fileName());
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             OutputStream outputStream = Files.newOutputStream(workbookPath)) {
            Sheet sheet = workbook.createSheet("내역1");
            CellStyle dateStyle = workbook.createCellStyle();
            dateStyle.setDataFormat(workbook.getCreationHelper().createDataFormat().getFormat("m/d"));

            Row header = sheet.createRow(1);
            header.createCell(1).setCellValue("연번");
            header.createCell(2).setCellValue("성명");
            header.createCell(3).setCellValue("전화번호");
            header.createCell(4).setCellValue(Date.valueOf(date));
            header.getCell(4).setCellStyle(dateStyle);

            for (int index = 0; index < people.length; index++) {
                Person person = people[index];
                Row row = sheet.createRow(3 + index);
                row.createCell(1).setCellValue(person.serialNumber());
                row.createCell(2).setCellValue(person.name());
                row.createCell(3).setCellValue(person.phoneNumber());
                row.createCell(4).setCellValue(person.attended() ? "o" : "");
            }
            if (hasDailyCountFormula) {
                sheet.createRow(3 + people.length + 1)
                        .createCell(4)
                        .setCellFormula("COUNTIF(E4:E5,\"O\")");
            }
            workbook.write(outputStream);
        }
    }

    private record Person(String serialNumber, String name, String phoneNumber, boolean attended) {
        private Person(String serialNumber, String name, boolean attended) {
            this(serialNumber, name, "", attended);
        }
    }

    private enum YearMonthFile {
        JANUARY("무료급식 일일 식사내역_26.1_일지.xlsx"),
        FEBRUARY("무료급식 일일 식사내역_26.2_일지.xlsx");

        private final String fileName;

        YearMonthFile(String fileName) {
            this.fileName = fileName;
        }

        String fileName() {
            return fileName;
        }
    }
}
