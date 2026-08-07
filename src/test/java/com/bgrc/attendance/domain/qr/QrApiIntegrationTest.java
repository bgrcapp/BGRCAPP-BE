package com.bgrc.attendance.domain.qr;

import com.bgrc.attendance.domain.qr.service.AttendanceService;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Date;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@AutoConfigureMockMvc
class QrApiIntegrationTest {
    private static final String TEST_NAME = "테스트 사용자";
    private static final String TEST_BIRTH_DATE = "1990-01-01";
    private static final String TEST_ISSUER = "북구장애인종합복지관";
    private static final LocalDate TEST_DATE = LocalDate.now();

    private static Path testDirectory;
    private static Path rosterDirectory;
    private static Path attendanceDirectory;
    private static Path attendanceWorkbook;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AttendanceService attendanceService;

    @AfterAll
    static void removeTestEnvironment() throws IOException {
        if (testDirectory == null || !Files.exists(testDirectory)) return;
        try (var paths = Files.walk(testDirectory)) {
            paths.sorted(Comparator.reverseOrder())
                    .forEach(path -> path.toFile().deleteOnExit());
        }
    }

    @DynamicPropertySource
    static void registerTestProperties(DynamicPropertyRegistry registry) {
        ensureTestEnvironment();
        registry.add("file.upload.dir", () -> rosterDirectory.toString());
        registry.add("attendance.log.dir", () -> attendanceDirectory.toString());
        registry.add("attendance.log.excel", () -> attendanceWorkbook.toString());
        registry.add("attendance.log.sheets", () -> "내역1,내역2");
    }

    @Test
    void scanApiRecordsExcelRejectsDuplicateAndRecoversAfterRestart() throws Exception {
        String request = "{\"qrData\":\"%s/%s/%s\"}"
                .formatted(TEST_NAME, TEST_BIRTH_DATE, TEST_ISSUER);

        mockMvc.perform(get("/api/admin/attendance/today"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1002))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.date").value(TEST_DATE.toString()))
                .andExpect(jsonPath("$.data.totalCount").value(1))
                .andExpect(jsonPath("$.data.checkedCount").value(0))
                .andExpect(jsonPath("$.data.people[0].name").value(TEST_NAME))
                .andExpect(jsonPath("$.data.people[0].attended").value(false));

        mockMvc.perform(post("/api/qr/scan")
                        .contentType(APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.userInfo.name").value(TEST_NAME));

        assertThatAttendanceWasRecorded();

        mockMvc.perform(get("/api/admin/attendance/today"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(1))
                .andExpect(jsonPath("$.data.checkedCount").value(1))
                .andExpect(jsonPath("$.data.uncheckedCount").value(0))
                .andExpect(jsonPath("$.data.people[0].attended").value(true));

        mockMvc.perform(post("/api/qr/scan")
                        .contentType(APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(3200))
                .andExpect(jsonPath("$.success").value(false));

        // 서버 재시작과 동일하게 메모리를 다시 초기화해도 기존 출석이 중복으로 차단되는지 확인합니다.
        attendanceService.shutdown();
        attendanceService.init();

        mockMvc.perform(post("/api/qr/scan")
                        .contentType(APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(3200))
                .andExpect(jsonPath("$.success").value(false));
    }

    private void assertThatAttendanceWasRecorded() throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook(Files.newInputStream(attendanceWorkbook))) {
            assertThat(workbook.getSheet("내역1").getRow(2).getCell(4).getStringCellValue())
                    .isEqualTo("o");
        }

        Path confirmationLog = attendanceDirectory.resolve(
                "무료급식 일일 식사내역_%s.txt"
                        .formatted(TEST_DATE.format(DateTimeFormatter.ofPattern("yyyy.MM.dd"))));
        List<String> lines = Files.readAllLines(confirmationLog);
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0)).isEqualTo("[출석 인원 : 1]");
        assertThat(lines.get(1)).startsWith(TEST_NAME + "/" + TEST_BIRTH_DATE + "/");
    }

    private static void ensureTestEnvironment() {
        if (testDirectory != null) return;
        try {
            testDirectory = Files.createTempDirectory("bgrc-api-test-");
            rosterDirectory = Files.createDirectories(testDirectory.resolve("userlist"));
            attendanceDirectory = Files.createDirectories(testDirectory.resolve("attendance"));
            attendanceWorkbook = attendanceDirectory.resolve("attendance.xlsx");

            createRosterWorkbook(rosterDirectory.resolve("roster.xlsx"));
            createAttendanceWorkbook(attendanceWorkbook);
        } catch (IOException e) {
            throw new IllegalStateException("API 통합 테스트 환경 생성 실패", e);
        }
    }

    private static void createRosterWorkbook(Path path) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             OutputStream outputStream = Files.newOutputStream(path)) {
            Sheet sheet = workbook.createSheet("명단");
            Row header = sheet.createRow(1);
            header.createCell(0).setCellValue("연번");
            header.createCell(1).setCellValue("성명");
            header.createCell(2).setCellValue("생년월일");

            Row data = sheet.createRow(2);
            data.createCell(0).setCellValue(1);
            data.createCell(1).setCellValue(TEST_NAME);
            data.createCell(2).setCellValue("19900101");
            workbook.write(outputStream);
        }
    }

    private static void createAttendanceWorkbook(Path path) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             OutputStream outputStream = Files.newOutputStream(path)) {
            CellStyle dateStyle = workbook.createCellStyle();
            dateStyle.setDataFormat(workbook.getCreationHelper().createDataFormat().getFormat("m/d"));

            Sheet sheet = workbook.createSheet("내역1");
            Row header = sheet.createRow(1);
            header.createCell(1).setCellValue("연번");
            header.createCell(2).setCellValue("성명");
            header.createCell(4).setCellValue(Date.valueOf(TEST_DATE));
            header.getCell(4).setCellStyle(dateStyle);

            Row data = sheet.createRow(2);
            data.createCell(1).setCellValue(1);
            data.createCell(2).setCellValue(TEST_NAME);
            data.createCell(4);
            workbook.createSheet("내역2");
            workbook.write(outputStream);
        }
    }
}
