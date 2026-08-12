package com.bgrc.attendance.domain.qr;

import com.bgrc.attendance.domain.qr.service.AttendanceService;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Date;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.stream.IntStream;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.http.HttpHeaders.CONTENT_DISPOSITION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
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
    private static Path monthlyDirectory;
    private static Path attendanceTemplate;
    private static Path attendanceWorkbook;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AttendanceService attendanceService;

    @Autowired
    private BuildProperties buildProperties;

    @Test
    void rootForwardsToTheAdminPage() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/admin.html"));
    }

    @Test
    void faviconFallbackDoesNotReturnAnInternalServerError() throws Exception {
        mockMvc.perform(get("/favicon.ico"))
                .andExpect(status().isNoContent());
    }

    @Test
    void versionApiReturnsTheBuildVersionForTheLauncherHealthCheck() throws Exception {
        mockMvc.perform(get("/api/version"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(buildProperties.getVersion()));

        mockMvc.perform(get("/api/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(buildProperties.getVersion()));
    }

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
        registry.add("attendance.log.template", () -> attendanceTemplate.toString());
        registry.add("attendance.log.monthly-dir", () -> monthlyDirectory.toString());
        registry.add("attendance.log.sheets", () -> "내역1,내역2");
    }

    @Test
    void scanApiRecordsGeneratedMonthlyLedgerRejectsDuplicateAndRecoversAfterRestart() throws Exception {
        String request = "{\"qrData\":\"%s/%s/%s\"}"
                .formatted(TEST_NAME, TEST_BIRTH_DATE, TEST_ISSUER);

        MockMultipartFile roster = new MockMultipartFile(
                "file",
                "today-roster.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                Files.readAllBytes(rosterDirectory.resolve("roster.xlsx")));
        mockMvc.perform(multipart("/api/admin/roster").file(roster))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1001))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.userCount").value(1))
                .andExpect(jsonPath("$.data.rosterFileName").value("attendance-roster.xlsx"));

        mockMvc.perform(get("/api/admin/attendance").param("date", TEST_DATE.toString()))
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

        mockMvc.perform(get("/api/admin/attendance").param("date", TEST_DATE.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(1))
                .andExpect(jsonPath("$.data.checkedCount").value(1))
                .andExpect(jsonPath("$.data.uncheckedCount").value(0))
                .andExpect(jsonPath("$.data.people[0].serialNumber").value("1"))
                .andExpect(jsonPath("$.data.people[0].attended").value(true));

        // 관리자 화면에서는 출석/결석 배지를 눌러 Excel의 o를 즉시 반대로 바꿀 수 있다.
        mockMvc.perform(post("/api/admin/attendance/toggle")
                        .param("date", TEST_DATE.toString())
                        .param("serialNumber", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1002))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.checkedCount").value(0))
                .andExpect(jsonPath("$.data.people[0].attended").value(false));
        assertThatAttendanceWasCleared();

        // 오늘 출석을 결석으로 되돌리면 서버 메모리에서도 즉시 해제되어 QR 재출석이 가능하다.
        mockMvc.perform(post("/api/qr/scan")
                        .contentType(APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.success").value(true));

        mockMvc.perform(get("/api/admin/attendance/export").param("date", TEST_DATE.toString()))
                .andExpect(status().isOk())
                .andExpect(header().string(CONTENT_DISPOSITION, org.hamcrest.Matchers.containsString("attachment")))
                .andExpect(header().string(CONTENT_DISPOSITION, org.hamcrest.Matchers.containsString(".xlsx")))
                .andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .andExpect(content().bytes(Files.readAllBytes(attendanceWorkbook)));

        mockMvc.perform(get("/api/admin/attendance").param("date", TEST_DATE.minusDays(1).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.date").value(TEST_DATE.minusDays(1).toString()))
                .andExpect(jsonPath("$.data.totalCount").value(1))
                .andExpect(jsonPath("$.data.checkedCount").value(0))
                .andExpect(jsonPath("$.data.people[0].attended").value(false));

        mockMvc.perform(post("/api/qr/scan")
                        .contentType(APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(3200))
                .andExpect(jsonPath("$.success").value(false));

        // 서버 재시작과 동일하게 메모리를 다시 초기화해도 기존 출석이 중복으로 차단되는지 확인합니다.
        attendanceService.reloadCurrentDate();

        mockMvc.perform(post("/api/qr/scan")
                        .contentType(APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(3200))
                .andExpect(jsonPath("$.success").value(false));
    }

    private void assertThatAttendanceWasRecorded() throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook(Files.newInputStream(attendanceWorkbook))) {
            Sheet sheet = workbook.getSheet("내역1");
            Row header = sheet.getRow(1);
            int dateColumn = IntStream.range(4, header.getLastCellNum())
                    .filter(column -> header.getCell(column) != null
                            && org.apache.poi.ss.usermodel.DateUtil.isCellDateFormatted(header.getCell(column)))
                    .filter(column -> org.apache.poi.ss.usermodel.DateUtil.getJavaDate(header.getCell(column).getNumericCellValue())
                            .toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDate().equals(TEST_DATE))
                    .findFirst()
                    .orElseThrow();
            assertThat(sheet.getRow(3).getCell(dateColumn).getStringCellValue())
                    .isEqualTo("o");
        }

        try (var files = Files.list(attendanceDirectory)) {
            assertThat(files.map(path -> path.getFileName().toString()))
                    .noneMatch(fileName -> fileName.endsWith(".txt"));
        }
    }

    private void assertThatAttendanceWasCleared() throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook(Files.newInputStream(attendanceWorkbook))) {
            Sheet sheet = workbook.getSheet("내역1");
            Row header = sheet.getRow(1);
            int dateColumn = IntStream.range(4, header.getLastCellNum())
                    .filter(column -> header.getCell(column) != null
                            && org.apache.poi.ss.usermodel.DateUtil.isCellDateFormatted(header.getCell(column)))
                    .filter(column -> org.apache.poi.ss.usermodel.DateUtil.getJavaDate(header.getCell(column).getNumericCellValue())
                            .toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDate().equals(TEST_DATE))
                    .findFirst()
                    .orElseThrow();
            assertThat(sheet.getRow(3).getCell(dateColumn).getStringCellValue()).isBlank();
        }
    }

    private static void ensureTestEnvironment() {
        if (testDirectory != null) return;
        try {
            testDirectory = Files.createTempDirectory("bgrc-api-test-");
            rosterDirectory = Files.createDirectories(testDirectory.resolve("userlist"));
            attendanceDirectory = Files.createDirectories(testDirectory.resolve("attendance"));
            monthlyDirectory = Files.createDirectories(attendanceDirectory.resolve("monthly"));
            attendanceTemplate = attendanceDirectory.resolve("attendance-template.xlsx");
            attendanceWorkbook = monthlyDirectory.resolve("무료급식 일일 식사내역_%02d.%d_일지.xlsx"
                    .formatted(TEST_DATE.getYear() % 100, TEST_DATE.getMonthValue()));

            createRosterWorkbook(rosterDirectory.resolve("roster.xlsx"));
            createAttendanceTemplate(attendanceTemplate);
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

    private static void createAttendanceTemplate(Path path) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             OutputStream outputStream = Files.newOutputStream(path)) {
            CellStyle dateStyle = workbook.createCellStyle();
            dateStyle.setDataFormat(workbook.getCreationHelper().createDataFormat().getFormat("m/d"));

            createTemplateSheet(workbook, "내역1", dateStyle);
            createTemplateSheet(workbook, "내역2", dateStyle);
            workbook.write(outputStream);
        }
    }

    private static void createTemplateSheet(XSSFWorkbook workbook, String sheetName, CellStyle dateStyle) {
        Sheet sheet = workbook.createSheet(sheetName);
        Row header = sheet.createRow(1);
        header.createCell(1).setCellValue("연번");
        header.createCell(2).setCellValue("성명");
        header.createCell(4).setCellValue(Date.valueOf(TEST_DATE));
        header.getCell(4).setCellStyle(dateStyle);

        Row weekday = sheet.createRow(2);
        weekday.createCell(4).setCellValue("월");
        Row prototype = sheet.createRow(3);
        prototype.createCell(1);
        prototype.createCell(2);
        prototype.createCell(4);
    }
}
