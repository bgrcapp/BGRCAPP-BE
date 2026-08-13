package com.bgrc.attendance.domain.user.service;

import com.bgrc.attendance.domain.user.config.ExcelUploadConfig;
import com.bgrc.attendance.domain.user.repository.UserRepository;
import com.bgrc.attendance.global.util.ExcelFileUtils;
import com.bgrc.attendance.global.util.RuntimeDataPathResolver;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserServiceTest {

    @TempDir
    Path uploadDirectory;

    @Test
    void excludesRowWithoutSerialNumberBeforeReadingBirthDate() throws Exception {
        Path excelFile = uploadDirectory.resolve("users.xlsx");
        createWorkbook(excelFile);

        ExcelUploadConfig excelUploadConfig = mock(ExcelUploadConfig.class);
        when(excelUploadConfig.getUploadDir()).thenReturn(uploadDirectory.toString());

        UserRepository userRepository = new UserRepository();
        ExcelFileUtils excelFileUtils = new ExcelFileUtils(excelUploadConfig);
        UserService userService = new UserService(userRepository, excelUploadConfig, excelFileUtils,
                new RuntimeDataPathResolver());

        userService.loadUsersFromExcel();

        assertThat(userRepository.count()).isEqualTo(1);
        assertThat(userRepository.findByNameAndBirthDate("이용자", "1959-03-27")).isTrue();
        assertThat(userRepository.findUser("이용자", "1959-03-27").orElseThrow().getPhoneNumber())
                .isEqualTo("010-1234-5678");
        assertThat(userRepository.findByNameAndBirthDate("종결자", "")).isFalse();
    }

    @Test
    void formatsStringBirthDateCell() throws Exception {
        ExcelUploadConfig excelUploadConfig = mock(ExcelUploadConfig.class);
        ExcelFileUtils excelFileUtils = new ExcelFileUtils(excelUploadConfig);

        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Row row = workbook.createSheet("명단").createRow(0);
            Cell compactCell = row.createCell(0);
            compactCell.setCellValue("19900101");
            Cell hyphenCell = row.createCell(1);
            hyphenCell.setCellValue("1990-01-01");
            Cell slashCell = row.createCell(2);
            slashCell.setCellValue("1990/01/01");

            assertThat(excelFileUtils.formatBirthDate(compactCell)).isEqualTo("1990-01-01");
            assertThat(excelFileUtils.formatBirthDate(hyphenCell)).isEqualTo("1990-01-01");
            assertThat(excelFileUtils.formatBirthDate(slashCell)).isEqualTo("1990-01-01");
        }
    }

    @Test
    void replacesActiveRosterOnlyAfterTheUploadedWorkbookIsParsed() throws Exception {
        Path uploadFile = uploadDirectory.resolve("received-roster.xlsx");
        createWorkbook(uploadFile);

        ExcelUploadConfig excelUploadConfig = mock(ExcelUploadConfig.class);
        when(excelUploadConfig.getUploadDir()).thenReturn(uploadDirectory.toString());

        UserRepository userRepository = new UserRepository();
        ExcelFileUtils excelFileUtils = new ExcelFileUtils(excelUploadConfig);
        UserService userService = new UserService(userRepository, excelUploadConfig, excelFileUtils,
                new RuntimeDataPathResolver());
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "received-roster.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                Files.readAllBytes(uploadFile));

        assertThat(userService.replaceRoster(file)).isEqualTo(1);
        assertThat(userService.getActiveRosterFileName()).isEqualTo("attendance-roster.xlsx");
        assertThat(userService.hasRosterFile()).isTrue();
        assertThat(userRepository.findByNameAndBirthDate("이용자", "1959-03-27")).isTrue();
        try (var files = Files.list(uploadDirectory)) {
            assertThat(files.filter(path -> path.getFileName().toString().endsWith(".xlsx")).count()).isEqualTo(1);
        }
    }

    @Test
    void restoresThePreviousRosterWhenMonthlyLedgerSynchronizationFails() throws Exception {
        ExcelUploadConfig excelUploadConfig = mock(ExcelUploadConfig.class);
        when(excelUploadConfig.getUploadDir()).thenReturn(uploadDirectory.toString());

        UserRepository userRepository = new UserRepository();
        UserService userService = new UserService(userRepository, excelUploadConfig,
                new ExcelFileUtils(excelUploadConfig), new RuntimeDataPathResolver());

        Path previousFile = Files.createTempFile("previous-roster-", ".xlsx");
        Path replacementFile = Files.createTempFile("replacement-roster-", ".xlsx");
        createWorkbook(previousFile, "기존이용자");
        createWorkbook(replacementFile, "새이용자");

        userService.replaceRoster(toMultipartFile(previousFile));
        UserService.RosterReplacement replacement = userService.replaceRosterWithRollback(toMultipartFile(replacementFile));
        assertThat(userRepository.findByNameAndBirthDate("새이용자", "1959-03-27")).isTrue();

        replacement.rollback();

        assertThat(userService.getActiveRosterFileName()).isEqualTo("attendance-roster.xlsx");
        assertThat(userRepository.findByNameAndBirthDate("기존이용자", "1959-03-27")).isTrue();
        assertThat(userRepository.findByNameAndBirthDate("새이용자", "1959-03-27")).isFalse();
    }

    private MockMultipartFile toMultipartFile(Path excelFile) throws Exception {
        return new MockMultipartFile(
                "file",
                excelFile.getFileName().toString(),
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                Files.readAllBytes(excelFile));
    }

    private void createWorkbook(Path excelFile) throws Exception {
        createWorkbook(excelFile, "이용자");
    }

    private void createWorkbook(Path excelFile, String userName) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             OutputStream outputStream = Files.newOutputStream(excelFile)) {
            Sheet sheet = workbook.createSheet("이용자");
            Row header = sheet.createRow(0);
            header.createCell(1).setCellValue("연번");
            header.createCell(2).setCellValue("성명");
            header.createCell(4).setCellValue("전화번호");
            header.createCell(5).setCellValue("생년월일");

            CellStyle dateStyle = workbook.createCellStyle();
            dateStyle.setDataFormat(workbook.getCreationHelper().createDataFormat().getFormat("m/d/yy"));

            Row activeUserRow = sheet.createRow(1);
            activeUserRow.createCell(1).setCellValue(1);
            activeUserRow.createCell(2).setCellValue(userName);
            activeUserRow.createCell(4).setCellValue("010-1234-5678");
            Cell activeUserBirthDate = activeUserRow.createCell(5);
            activeUserBirthDate.setCellValue(Date.valueOf("1959-03-27"));
            activeUserBirthDate.setCellStyle(dateStyle);

            Row formerUserRow = sheet.createRow(2);
            formerUserRow.createCell(2).setCellValue("종결자");
            Cell formerUserBirthDate = formerUserRow.createCell(5);
            formerUserBirthDate.setCellStyle(dateStyle);

            workbook.write(outputStream);
        }
    }
}
