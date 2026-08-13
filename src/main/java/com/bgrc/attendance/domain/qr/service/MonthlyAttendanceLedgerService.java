package com.bgrc.attendance.domain.qr.service;

import com.bgrc.attendance.domain.qr.config.AttendanceLogConfig;
import com.bgrc.attendance.domain.user.model.User;
import com.bgrc.attendance.global.common.CustomException;
import com.bgrc.attendance.global.common.ResponseCode;
import com.bgrc.attendance.global.util.RuntimeDataPathResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Service;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Date;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 출석 양식을 복제해 활성 이용자 명단 기준의 연·월별 출석 일지를 만든다.
 * 원본 양식 파일은 수정하지 않고 data/attendance 아래에 연·월 파일명으로 생성한다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MonthlyAttendanceLedgerService {
    private static final int HEADER_SEARCH_ROWS = 10;
    private static final int USERS_PER_SHEET = 25;
    private static final String ATTENDANCE_MARK = "o";
    private final AttendanceLogConfig attendanceLogConfig;
    private final KoreanHolidayCalendar holidayCalendar;
    private final RuntimeDataPathResolver runtimeDataPathResolver;
    private final DataFormatter dataFormatter = new DataFormatter();

    public synchronized Path ensureLedger(LocalDate date, List<User> users) {
        Path ledgerPath = getWorkbookPath(date);
        if (Files.exists(ledgerPath) || users.isEmpty()) return ledgerPath;
        Path legacyPath = legacyMonthlyWorkbookPath(date);
        // 기존 버전의 data/attendance/monthly 파일이 있으면 출석(o)을 살려 새 경로에 생성한다.
        rebuildLedger(date, users, readExistingMarksFromLegacyMonthlyDirectory(date, ledgerPath));
        archiveLegacyMonthlyLedger(legacyPath, ledgerPath);
        return ledgerPath;
    }

    /** 새 원본 명단을 올리면 당월 일지를 새 명단으로 교체하고 기존 `o` 표시는 보존한다. */
    public synchronized Path synchronizeCurrentMonth(LocalDate date, List<User> users) {
        Path ledgerPath = getWorkbookPath(date);
        if (users.isEmpty()) return ledgerPath;
        Path legacyPath = legacyMonthlyWorkbookPath(date);
        Map<String, Set<LocalDate>> existingMarks = new HashMap<>();
        if (Files.exists(ledgerPath)) {
            mergeMarks(existingMarks, readExistingMarks(ledgerPath));
        }
        // monthly 하위 폴더는 더 이상 쓰지 않지만, 이전에 기록된 출석은 새 파일로 한 번 이어받는다.
        mergeMarks(existingMarks, readExistingMarksFromLegacyMonthlyDirectory(date, ledgerPath));
        rebuildLedger(date, users, existingMarks);
        archiveLegacyMonthlyLedger(legacyPath, ledgerPath);
        return ledgerPath;
    }

    public Path getWorkbookPath(LocalDate date) {
        return configuredLedgerDirectory().resolve(ledgerFileName(date));
    }

    private Path configuredLedgerDirectory() {
        String directory = attendanceLogConfig.getMonthlyDir();
        return directory == null || directory.isBlank()
                ? runtimeDataPathResolver.resolve(attendanceLogConfig.getLogDir())
                : runtimeDataPathResolver.resolve(directory);
    }

    private Path legacyMonthlyWorkbookPath(LocalDate date) {
        return runtimeDataPathResolver.resolve(attendanceLogConfig.getLogDir())
                .resolve("monthly")
                .resolve(ledgerFileName(date));
    }

    private String ledgerFileName(LocalDate date) {
        return "무료급식 일일 식사내역_%02d.%d_일지.xlsx"
                .formatted(date.getYear() % 100, date.getMonthValue());
    }

    private Map<String, Set<LocalDate>> readExistingMarksFromLegacyMonthlyDirectory(LocalDate date,
                                                                                        Path activeLedgerPath) {
        Path legacyPath = legacyMonthlyWorkbookPath(date);
        if (legacyPath.equals(activeLedgerPath) || !Files.isRegularFile(legacyPath)) return Map.of();
        log.info("기존 monthly 출석 일지를 새 저장 위치로 이관합니다: {}", legacyPath);
        return readExistingMarks(legacyPath);
    }

    private void mergeMarks(Map<String, Set<LocalDate>> destination,
                            Map<String, Set<LocalDate>> source) {
        source.forEach((userKey, markedDates) -> destination
                .computeIfAbsent(userKey, ignored -> new HashSet<>())
                .addAll(markedDates));
    }

    /** 새 위치 저장이 끝난 뒤 구 monthly 파일은 보관 폴더로 옮겨 중복 사용하지 않는다. */
    private void archiveLegacyMonthlyLedger(Path legacyPath, Path activeLedgerPath) {
        if (legacyPath.equals(activeLedgerPath) || !Files.isRegularFile(legacyPath)) return;
        Path archiveDirectory = runtimeDataPathResolver.resolve(attendanceLogConfig.getLogDir())
                .resolve("legacy-backups")
                .resolve("monthly-directory");
        Path archivePath = archiveDirectory.resolve(legacyPath.getFileName()
                + ".migrated-" + System.currentTimeMillis());
        try {
            Files.createDirectories(archiveDirectory);
            moveAtomically(legacyPath, archivePath);
            log.info("기존 monthly 출석 일지를 보관 폴더로 이전했습니다: {}", archivePath);
        } catch (IOException e) {
            // 새 활성 파일은 이미 완성돼 있으므로 출석 흐름은 유지한다. 다음 동기화 때 다시 이전을 시도한다.
            log.warn("기존 monthly 출석 일지 보관 처리 실패: {}", legacyPath, e);
        }
    }

    private void rebuildLedger(LocalDate date,
                               List<User> users,
                               Map<String, Set<LocalDate>> existingMarks) {
        Path ledgerPath = getWorkbookPath(date);
        Path tempPath = null;
        try {
            Files.createDirectories(ledgerPath.getParent());
            tempPath = Files.createTempFile(ledgerPath.getParent(), ".monthly-ledger-", ".xlsx");
            copyTemplate(tempPath);

            try (InputStream inputStream = Files.newInputStream(tempPath);
                 Workbook workbook = WorkbookFactory.create(inputStream)) {
                List<LocalDate> businessDays = getBusinessDays(YearMonth.from(date));
                List<String> sheetNames = getConfiguredSheetNames();
                for (int sheetIndex = 0; sheetIndex < sheetNames.size(); sheetIndex++) {
                    Sheet sheet = workbook.getSheet(sheetNames.get(sheetIndex));
                    if (sheet == null) continue;
                    Layout layout = findLayout(sheet);
                    if (layout == null) continue;

                    int fromIndex = sheetIndex * USERS_PER_SHEET;
                    int toIndex = sheetIndex == sheetNames.size() - 1
                            ? users.size()
                            : Math.min(fromIndex + USERS_PER_SHEET, users.size());
                    List<User> usersForSheet = fromIndex >= users.size()
                            ? List.of()
                            : users.subList(fromIndex, toIndex);
                    rebuildSheet(sheet, layout, usersForSheet, businessDays, existingMarks);
                }

                try (OutputStream outputStream = Files.newOutputStream(tempPath)) {
                    workbook.write(outputStream);
                }
            }

            moveAtomically(tempPath, ledgerPath);
            tempPath = null;
            log.info("{}월 출석 일지 {}명 생성 완료: {}", date.getYear() + "-" + date.getMonthValue(), users.size(), ledgerPath);
        } catch (IOException e) {
            throw new CustomException(ResponseCode.ATTENDANCE_LOG_WRITE_FAILED);
        } finally {
            if (tempPath != null) {
                try {
                    Files.deleteIfExists(tempPath);
                } catch (IOException cleanupException) {
                    log.warn("월별 출석 일지 임시 파일 삭제 실패: {}", tempPath, cleanupException);
                }
            }
        }
    }

    private Map<String, Set<LocalDate>> readExistingMarks(Path ledgerPath) {
        Map<String, Set<LocalDate>> marks = new HashMap<>();
        try (InputStream inputStream = Files.newInputStream(ledgerPath);
             Workbook workbook = WorkbookFactory.create(inputStream)) {
            for (String sheetName : getConfiguredSheetNames()) {
                Sheet sheet = workbook.getSheet(sheetName);
                if (sheet == null) continue;
                Layout layout = findLayout(sheet);
                if (layout == null) continue;

                for (int rowIndex = findDataStartRow(sheet, layout); rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                    Row row = sheet.getRow(rowIndex);
                    if (row == null) continue;
                    String serial = cellText(row.getCell(layout.serialNumberColumn()));
                    String name = cellText(row.getCell(layout.nameColumn()));
                    if (serial.isBlank() || name.isBlank()) continue;

                    for (int columnIndex = layout.firstDateColumn(); columnIndex < sheet.getRow(layout.headerRowIndex()).getLastCellNum(); columnIndex++) {
                        LocalDate markedDate = toLocalDate(sheet.getRow(layout.headerRowIndex()).getCell(columnIndex));
                        if (markedDate != null && isMarked(row.getCell(columnIndex))) {
                            String phoneNumber = layout.phoneNumberColumn() < 0
                                    ? ""
                                    : cellText(row.getCell(layout.phoneNumberColumn()));
                            marks.computeIfAbsent(userKey(serial, name, phoneNumber), ignored -> new HashSet<>())
                                    .add(markedDate);
                        }
                    }
                }
            }
            return marks;
        } catch (IOException e) {
            throw new CustomException(ResponseCode.ATTENDANCE_LOG_READ_FAILED);
        }
    }

    private void rebuildSheet(Sheet sheet,
                              Layout layout,
                              List<User> users,
                              List<LocalDate> businessDays,
                              Map<String, Set<LocalDate>> existingMarks) {
        Row header = sheet.getRow(layout.headerRowIndex());
        int weekdayRowIndex = layout.headerRowIndex() + 1;
        Row weekdayRow = getOrCreateRow(sheet, weekdayRowIndex);
        int dataStartRow = findDataStartRow(sheet, layout);
        Row prototypeRow = getOrCreateRow(sheet, dataStartRow);

        CellStyle dateHeaderStyle = cellStyle(header.getCell(layout.firstDateColumn()));
        CellStyle weekdayStyle = cellStyle(weekdayRow.getCell(layout.firstDateColumn()));
        CellStyle serialStyle = cellStyle(prototypeRow.getCell(layout.serialNumberColumn()));
        CellStyle nameStyle = cellStyle(prototypeRow.getCell(layout.nameColumn()));
        CellStyle phoneNumberStyle = layout.phoneNumberColumn() < 0
                ? null
                : cellStyle(prototypeRow.getCell(layout.phoneNumberColumn()));
        CellStyle attendanceStyle = cellStyle(prototypeRow.getCell(layout.firstDateColumn()));

        int maxDateColumn = Math.max(
                header.getLastCellNum() - 1,
                layout.firstDateColumn() + businessDays.size() - 1);
        for (int columnIndex = layout.firstDateColumn(); columnIndex <= maxDateColumn; columnIndex++) {
            clearCell(header, columnIndex, dateHeaderStyle);
            clearCell(weekdayRow, columnIndex, weekdayStyle);
        }
        for (int index = 0; index < businessDays.size(); index++) {
            LocalDate businessDay = businessDays.get(index);
            int columnIndex = layout.firstDateColumn() + index;
            setDateCell(header, columnIndex, businessDay, dateHeaderStyle);
            setTextCell(weekdayRow, columnIndex, dayOfWeekText(businessDay), weekdayStyle);
        }

        int rowCount = Math.max(sheet.getLastRowNum() - dataStartRow + 1, users.size());
        for (int rowOffset = 0; rowOffset < rowCount; rowOffset++) {
            Row row = getOrCreateRow(sheet, dataStartRow + rowOffset);
            User user = rowOffset < users.size() ? users.get(rowOffset) : null;
            for (int columnIndex = 0; columnIndex < layout.firstDateColumn(); columnIndex++) {
                if (columnIndex == layout.serialNumberColumn()
                        || columnIndex == layout.nameColumn()
                        || columnIndex == layout.phoneNumberColumn()) continue;
                clearCell(row, columnIndex, cellStyle(prototypeRow.getCell(columnIndex)));
            }
            setTextCell(row, layout.serialNumberColumn(), user == null ? "" : user.getSerialNumber(), serialStyle);
            setTextCell(row, layout.nameColumn(), user == null ? "" : user.getName(), nameStyle);
            if (layout.phoneNumberColumn() >= 0) {
                setTextCell(row, layout.phoneNumberColumn(), user == null ? "" : user.getPhoneNumber(), phoneNumberStyle);
            }
            for (int columnIndex = layout.firstDateColumn(); columnIndex <= maxDateColumn; columnIndex++) {
                clearCell(row, columnIndex, attendanceStyle);
            }
            if (user == null) continue;

            Set<LocalDate> preservedDates = existingMarks.get(userKey(user));
            if (preservedDates == null) {
                // 이전 버전이 전화번호 칸을 비워 둔 2026년 8월 파일과도 한 번 호환한다.
                preservedDates = existingMarks.getOrDefault(userKey(user.getSerialNumber(), user.getName(), ""), Set.of());
            }
            for (int index = 0; index < businessDays.size(); index++) {
                if (preservedDates.contains(businessDays.get(index))) {
                    setTextCell(row, layout.firstDateColumn() + index, ATTENDANCE_MARK, attendanceStyle);
                }
            }
        }
    }

    private List<LocalDate> getBusinessDays(YearMonth month) {
        List<LocalDate> dates = new ArrayList<>();
        for (int day = 1; day <= month.lengthOfMonth(); day++) {
            LocalDate date = month.atDay(day);
            if (date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY) continue;
            if (!holidayCalendar.isHoliday(date)) dates.add(date);
        }
        return dates;
    }

    private Layout findLayout(Sheet sheet) {
        for (int rowIndex = 0; rowIndex <= Math.min(HEADER_SEARCH_ROWS, sheet.getLastRowNum()); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) continue;
            int serialColumn = -1;
            int nameColumn = -1;
            int phoneNumberColumn = -1;
            int firstDateColumn = -1;
            for (int columnIndex = 0; columnIndex < row.getLastCellNum(); columnIndex++) {
                String value = cellText(row.getCell(columnIndex));
                if (value.contains("연번")) serialColumn = columnIndex;
                if (value.contains("성명")) nameColumn = columnIndex;
                if (value.contains("전화번호") || value.contains("연락처")) phoneNumberColumn = columnIndex;
                if (firstDateColumn < 0 && toLocalDate(row.getCell(columnIndex)) != null) firstDateColumn = columnIndex;
            }
            if (serialColumn >= 0 && nameColumn >= 0) {
                return new Layout(rowIndex, serialColumn, nameColumn, phoneNumberColumn,
                        firstDateColumn >= 0 ? firstDateColumn : nameColumn + 2);
            }
        }
        return null;
    }

    private int findDataStartRow(Sheet sheet, Layout layout) {
        for (int rowIndex = layout.headerRowIndex() + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row != null && !cellText(row.getCell(layout.serialNumberColumn())).isBlank()) return rowIndex;
        }
        return layout.headerRowIndex() + 2;
    }

    private List<String> getConfiguredSheetNames() {
        return List.of(attendanceLogConfig.getSheetNames().split(","))
                .stream()
                .map(String::strip)
                .filter(name -> !name.isBlank())
                .toList();
    }

    private LocalDate toLocalDate(Cell cell) {
        if (cell == null || cell.getCellType() != CellType.NUMERIC
                || !DateUtil.isCellDateFormatted(cell)
                || !DateUtil.isValidExcelDate(cell.getNumericCellValue())) {
            return null;
        }
        return DateUtil.getJavaDate(cell.getNumericCellValue()).toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
    }

    private boolean isMarked(Cell cell) {
        return ATTENDANCE_MARK.equalsIgnoreCase(cellText(cell));
    }

    private String cellText(Cell cell) {
        return cell == null ? "" : dataFormatter.formatCellValue(cell).trim();
    }

    private String userKey(User user) {
        return userKey(user.getSerialNumber(), user.getName(), user.getPhoneNumber());
    }

    private String userKey(String serial, String name, String phoneNumber) {
        String normalizedPhoneNumber = normalizePhoneNumber(phoneNumber);
        if (!normalizedPhoneNumber.isBlank()) return name.strip() + ":" + normalizedPhoneNumber;
        return serial.strip() + ":" + name.strip();
    }

    private String normalizePhoneNumber(String phoneNumber) {
        return phoneNumber == null ? "" : phoneNumber.replaceAll("[^0-9]", "");
    }

    private Row getOrCreateRow(Sheet sheet, int rowIndex) {
        Row row = sheet.getRow(rowIndex);
        return row == null ? sheet.createRow(rowIndex) : row;
    }

    private CellStyle cellStyle(Cell cell) {
        return cell == null ? null : cell.getCellStyle();
    }

    private void clearCell(Row row, int columnIndex, CellStyle style) {
        Cell cell = getOrCreateCell(row, columnIndex);
        cell.setBlank();
        if (style != null) cell.setCellStyle(style);
    }

    private void setDateCell(Row row, int columnIndex, LocalDate date, CellStyle style) {
        Cell cell = getOrCreateCell(row, columnIndex);
        cell.setCellValue(Date.valueOf(date));
        if (style != null) cell.setCellStyle(style);
    }

    private void setTextCell(Row row, int columnIndex, String value, CellStyle style) {
        Cell cell = getOrCreateCell(row, columnIndex);
        cell.setCellValue(value);
        if (style != null) cell.setCellStyle(style);
    }

    private Cell getOrCreateCell(Row row, int columnIndex) {
        Cell cell = row.getCell(columnIndex);
        return cell == null ? row.createCell(columnIndex) : cell;
    }

    private String dayOfWeekText(LocalDate date) {
        return switch (date.getDayOfWeek()) {
            case MONDAY -> "월";
            case TUESDAY -> "화";
            case WEDNESDAY -> "수";
            case THURSDAY -> "목";
            case FRIDAY -> "금";
            default -> "";
        };
    }

    private void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void copyTemplate(Path target) throws IOException {
        String template = attendanceLogConfig.getTemplatePath();
        if (template != null && template.startsWith("classpath:")) {
            String resourcePath = template.substring("classpath:".length());
            ClassPathResource resource = new ClassPathResource(resourcePath);
            if (!resource.exists()) throw new CustomException(ResponseCode.ATTENDANCE_LOG_FILE_NOT_FOUND);
            try (InputStream inputStream = resource.getInputStream()) {
                Files.copy(inputStream, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return;
        }

        Path templatePath = runtimeDataPathResolver.resolve(template);
        if (!Files.exists(templatePath)) throw new CustomException(ResponseCode.ATTENDANCE_LOG_FILE_NOT_FOUND);
        Files.copy(templatePath, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private record Layout(int headerRowIndex,
                          int serialNumberColumn,
                          int nameColumn,
                          int phoneNumberColumn,
                          int firstDateColumn) {
    }
}
