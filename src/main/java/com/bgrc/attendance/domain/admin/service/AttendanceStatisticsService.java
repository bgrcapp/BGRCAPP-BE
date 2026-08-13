package com.bgrc.attendance.domain.admin.service;

import com.bgrc.attendance.domain.admin.dto.AttendanceStatisticsResponse;
import com.bgrc.attendance.domain.qr.config.AttendanceLogConfig;
import com.bgrc.attendance.domain.user.model.User;
import com.bgrc.attendance.domain.user.service.UserService;
import com.bgrc.attendance.global.util.RuntimeDataPathResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * data/attendance 바로 아래의 월별 출석 일지들을 읽어 누계·월별·일별·개인별 이용 현황을 만든다.
 *
 * <p>기존에 작성된 일지는 일별 총계가 COUNTIF 수식 범위로 결정된다. 과거 일지에 인원이
 * 추가됐지만 수식 범위가 늘지 않은 경우에도 기존 공식 누계와 통계가 같아야 하므로, 해당
 * 수식이 있는 날짜는 수식 범위에 포함되는 명단 행만 집계한다. 새 일지처럼 총계 수식이
 * 없는 경우에는 유효한 명단 행 전체를 집계한다.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AttendanceStatisticsService {
    private static final int HEADER_SEARCH_ROWS = 10;
    private static final String ATTENDANCE_MARK = "o";
    // macOS는 한글 파일명을 자모 분리 형태로 저장할 수 있으므로, 한글 접미사는 비교하지 않는다.
    private static final Pattern LEDGER_FILE_NAME = Pattern.compile(
            ".*_(\\d{2})\\.(\\d{1,2})_.*\\.xlsx$", Pattern.CASE_INSENSITIVE);
    private static final Pattern DAILY_COUNT_FORMULA = Pattern.compile(
            "(?i)^COUNTIF\\(\\$?([A-Z]+)\\$?(\\d+):\\$?([A-Z]+)\\$?(\\d+),\\s*\\\"?O\\\"?\\)$");
    private static final DateTimeFormatter MONTH_FORMATTER = DateTimeFormatter.ofPattern("yyyy년 M월");
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;

    private final AttendanceLogConfig attendanceLogConfig;
    private final RuntimeDataPathResolver runtimeDataPathResolver;
    private final UserService userService;
    private final DataFormatter dataFormatter = new DataFormatter();

    public AttendanceStatisticsResponse getStatistics() {
        Map<YearMonth, Path> ledgerFiles = findLedgerFiles();
        Map<YearMonth, MonthAccumulator> months = new TreeMap<>();
        Map<LocalDate, DayAccumulator> days = new TreeMap<>();
        Map<String, PersonAccumulator> people = new HashMap<>();
        Set<AttendanceKey> recordedAttendances = new HashSet<>();
        IdentityResolver identityResolver = new IdentityResolver(userService.getUsers());
        int sourceFileCount = 0;

        for (Map.Entry<YearMonth, Path> entry : ledgerFiles.entrySet()) {
            if (readLedger(entry.getValue(), entry.getKey(), months, days, people, recordedAttendances, identityResolver)) {
                sourceFileCount++;
            }
        }

        List<AttendanceStatisticsResponse.MonthlyAttendanceStatistics> monthlyStatistics = new ArrayList<>();
        int cumulativeMealCount = 0;
        for (Map.Entry<YearMonth, MonthAccumulator> entry : months.entrySet()) {
            cumulativeMealCount += entry.getValue().mealCount;
            monthlyStatistics.add(toMonthlyStatistics(entry.getKey(), entry.getValue(), cumulativeMealCount));
        }
        List<AttendanceStatisticsResponse.DailyAttendanceStatistics> dailyStatistics = new ArrayList<>();
        int dailyCumulativeMealCount = 0;
        for (Map.Entry<LocalDate, DayAccumulator> entry : days.entrySet()) {
            dailyCumulativeMealCount += entry.getValue().mealCount;
            dailyStatistics.add(toDailyStatistics(entry.getKey(), entry.getValue(), dailyCumulativeMealCount));
        }
        List<AttendanceStatisticsResponse.PersonAttendanceStatistics> personStatistics = people.values().stream()
                .map(this::toPersonStatistics)
                .sorted(Comparator
                        .comparingInt(AttendanceStatisticsResponse.PersonAttendanceStatistics::visitCount).reversed()
                        .thenComparing(AttendanceStatisticsResponse.PersonAttendanceStatistics::name)
                        .thenComparing(AttendanceStatisticsResponse.PersonAttendanceStatistics::serialNumber))
                .toList();

        int totalMealCount = monthlyStatistics.stream()
                .mapToInt(AttendanceStatisticsResponse.MonthlyAttendanceStatistics::mealCount)
                .sum();
        YearMonth latestMonth = months.isEmpty() ? null : months.keySet().stream().max(YearMonth::compareTo).orElseThrow();
        MonthAccumulator latest = latestMonth == null ? null : months.get(latestMonth);

        return new AttendanceStatisticsResponse(
                totalMealCount,
                people.size(),
                sourceFileCount,
                latest == null ? 0 : latest.mealCount,
                latestMonth == null ? "" : latestMonth.format(MONTH_FORMATTER),
                monthlyStatistics,
                dailyStatistics,
                personStatistics
        );
    }

    private Map<YearMonth, Path> findLedgerFiles() {
        Path directory = configuredLedgerDirectory();
        if (!Files.isDirectory(directory)) return Map.of();

        Map<YearMonth, Path> filesByMonth = new TreeMap<>();
        try (Stream<Path> paths = Files.list(directory)) {
            for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                Optional<YearMonth> yearMonth = yearMonthFromFileName(path.getFileName().toString());
                if (yearMonth.isEmpty()) continue;

                Path current = filesByMonth.get(yearMonth.get());
                if (current == null || isNewer(path, current)) {
                    filesByMonth.put(yearMonth.get(), path);
                }
            }
        } catch (IOException e) {
            log.warn("출석 통계 파일 목록을 읽지 못했습니다: {}", directory, e);
        }
        return filesByMonth;
    }

    private boolean isNewer(Path candidate, Path current) {
        try {
            return Files.getLastModifiedTime(candidate).compareTo(Files.getLastModifiedTime(current)) > 0;
        } catch (IOException ignored) {
            return false;
        }
    }

    private Optional<YearMonth> yearMonthFromFileName(String fileName) {
        if (fileName.startsWith("~$")) return Optional.empty();
        Matcher matcher = LEDGER_FILE_NAME.matcher(fileName);
        if (!matcher.matches()) return Optional.empty();
        try {
            int year = 2000 + Integer.parseInt(matcher.group(1));
            int month = Integer.parseInt(matcher.group(2));
            return Optional.of(YearMonth.of(year, month));
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private boolean readLedger(Path path,
                               YearMonth month,
                               Map<YearMonth, MonthAccumulator> months,
                               Map<LocalDate, DayAccumulator> days,
                               Map<String, PersonAccumulator> people,
                               Set<AttendanceKey> recordedAttendances,
                               IdentityResolver identityResolver) {
        try (InputStream inputStream = Files.newInputStream(path);
             Workbook workbook = WorkbookFactory.create(inputStream)) {
            for (String sheetName : getConfiguredSheetNames()) {
                Sheet sheet = workbook.getSheet(sheetName);
                if (sheet == null) continue;
                Layout layout = findLayout(sheet);
                if (layout == null) continue;
                readSheet(sheet, layout, month, months, days, people, recordedAttendances, identityResolver);
            }
            return true;
        } catch (IOException e) {
            log.warn("출석 통계에서 일지를 제외합니다. 파일을 읽을 수 없습니다: {}", path, e);
            return false;
        }
    }

    private void readSheet(Sheet sheet,
                           Layout layout,
                           YearMonth month,
                           Map<YearMonth, MonthAccumulator> months,
                           Map<LocalDate, DayAccumulator> days,
                           Map<String, PersonAccumulator> people,
                           Set<AttendanceKey> recordedAttendances,
                           IdentityResolver identityResolver) {
        Row header = sheet.getRow(layout.headerRowIndex());
        if (header == null) return;

        List<DateColumn> dateColumns = new ArrayList<>();
        for (int columnIndex = layout.firstDateColumn(); columnIndex < header.getLastCellNum(); columnIndex++) {
            LocalDate date = toLocalDate(header.getCell(columnIndex));
            if (date == null || !YearMonth.from(date).equals(month)) continue;
            dateColumns.add(new DateColumn(columnIndex, date, findFormulaAttendanceRange(sheet, layout, columnIndex)));
        }
        if (dateColumns.isEmpty()) return;

        int dataStartRow = findDataStartRow(sheet, layout);
        for (int rowIndex = dataStartRow; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) continue;
            String serialNumber = cellText(row.getCell(layout.serialNumberColumn()));
            String name = cellText(row.getCell(layout.nameColumn()));
            if (serialNumber.isBlank() || name.isBlank()) continue;

            String phoneNumber = layout.phoneNumberColumn() < 0
                    ? ""
                    : cellText(row.getCell(layout.phoneNumberColumn()));
            PersonIdentity identity = identityResolver.resolve(serialNumber, name, phoneNumber);
            for (DateColumn dateColumn : dateColumns) {
                if (!dateColumn.includes(rowIndex) || !isMarked(row.getCell(dateColumn.columnIndex()))) continue;
                AttendanceKey attendanceKey = new AttendanceKey(dateColumn.date(), identity.key());
                if (!recordedAttendances.add(attendanceKey)) continue;

                MonthAccumulator monthAccumulator = months.computeIfAbsent(month, ignored -> new MonthAccumulator());
                monthAccumulator.record(dateColumn.date(), identity.key());
                days.computeIfAbsent(dateColumn.date(), ignored -> new DayAccumulator())
                        .record(identity.key());
                people.computeIfAbsent(identity.key(), ignored -> new PersonAccumulator(identity.serialNumber(), identity.name()))
                        .record(dateColumn.date(), identity.serialNumber(), identity.name());
            }
        }
    }

    /**
     * 과거 일지의 일일 총계 수식(COUNTIF)이 있다면 그 행 범위를 그대로 집계 기준으로 쓴다.
     * 새 양식처럼 해당 수식이 없으면 null을 반환해 모든 유효 명단 행을 포함한다.
     */
    private RowRange findFormulaAttendanceRange(Sheet sheet, Layout layout, int dateColumnIndex) {
        String expectedColumn = columnName(dateColumnIndex);
        for (int rowIndex = layout.headerRowIndex() + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) continue;
            Cell cell = row.getCell(dateColumnIndex);
            if (cell == null || cell.getCellType() != CellType.FORMULA) continue;

            Matcher matcher = DAILY_COUNT_FORMULA.matcher(cell.getCellFormula().replaceAll("\\s+", ""));
            if (!matcher.matches()) continue;
            if (!expectedColumn.equalsIgnoreCase(matcher.group(1))
                    || !expectedColumn.equalsIgnoreCase(matcher.group(3))) continue;
            return new RowRange(Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(4)));
        }
        return null;
    }

    private AttendanceStatisticsResponse.MonthlyAttendanceStatistics toMonthlyStatistics(YearMonth month,
                                                                                            MonthAccumulator source,
                                                                                            int cumulativeMealCount) {
        int attendanceDays = source.attendanceDates.size();
        double average = attendanceDays == 0 ? 0 : (double) source.mealCount / attendanceDays;
        return new AttendanceStatisticsResponse.MonthlyAttendanceStatistics(
                month.format(MONTH_FORMATTER),
                source.mealCount,
                cumulativeMealCount,
                source.people.size(),
                attendanceDays,
                average
        );
    }

    private AttendanceStatisticsResponse.PersonAttendanceStatistics toPersonStatistics(PersonAccumulator source) {
        return new AttendanceStatisticsResponse.PersonAttendanceStatistics(
                source.serialNumber,
                source.name,
                source.visitCount,
                source.lastAttendance == null ? "" : source.lastAttendance.format(DATE_FORMATTER)
        );
    }

    private AttendanceStatisticsResponse.DailyAttendanceStatistics toDailyStatistics(LocalDate date,
                                                                                        DayAccumulator source,
                                                                                        int cumulativeMealCount) {
        return new AttendanceStatisticsResponse.DailyAttendanceStatistics(
                date.format(DATE_FORMATTER),
                source.mealCount,
                cumulativeMealCount,
                source.people.size()
        );
    }

    private Path configuredLedgerDirectory() {
        String directory = attendanceLogConfig.getMonthlyDir();
        return directory == null || directory.isBlank()
                ? runtimeDataPathResolver.resolve(attendanceLogConfig.getLogDir())
                : runtimeDataPathResolver.resolve(directory);
    }

    private List<String> getConfiguredSheetNames() {
        return Stream.of(attendanceLogConfig.getSheetNames().split(","))
                .map(String::strip)
                .filter(name -> !name.isBlank())
                .toList();
    }

    private Layout findLayout(Sheet sheet) {
        for (int rowIndex = 0; rowIndex <= Math.min(HEADER_SEARCH_ROWS, sheet.getLastRowNum()); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) continue;
            int serialNumberColumn = -1;
            int nameColumn = -1;
            int phoneNumberColumn = -1;
            int firstDateColumn = -1;
            for (int columnIndex = 0; columnIndex < row.getLastCellNum(); columnIndex++) {
                String value = cellText(row.getCell(columnIndex));
                if (value.contains("연번")) serialNumberColumn = columnIndex;
                if (value.contains("성명")) nameColumn = columnIndex;
                if (value.contains("전화번호") || value.contains("연락처")) phoneNumberColumn = columnIndex;
                if (firstDateColumn < 0 && toLocalDate(row.getCell(columnIndex)) != null) firstDateColumn = columnIndex;
            }
            if (serialNumberColumn >= 0 && nameColumn >= 0) {
                return new Layout(rowIndex, serialNumberColumn, nameColumn, phoneNumberColumn,
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

    private LocalDate toLocalDate(Cell cell) {
        if (cell == null || cell.getCellType() != CellType.NUMERIC
                || !DateUtil.isCellDateFormatted(cell)
                || !DateUtil.isValidExcelDate(cell.getNumericCellValue())) {
            return null;
        }
        return DateUtil.getJavaDate(cell.getNumericCellValue()).toInstant()
                .atZone(ZoneId.systemDefault()).toLocalDate();
    }

    private boolean isMarked(Cell cell) {
        return ATTENDANCE_MARK.equalsIgnoreCase(cellText(cell));
    }

    private String cellText(Cell cell) {
        return cell == null ? "" : dataFormatter.formatCellValue(cell).trim();
    }

    private String columnName(int index) {
        StringBuilder result = new StringBuilder();
        int value = index + 1;
        while (value > 0) {
            int remainder = (value - 1) % 26;
            result.insert(0, (char) ('A' + remainder));
            value = (value - 1) / 26;
        }
        return result.toString();
    }

    private String primaryKey(String name, String birthDate) {
        return name.strip() + "\u0000" + birthDate.strip();
    }

    private String nameAndPhoneKey(String name, String phoneNumber) {
        return name.strip() + "\u0000" + normalizePhoneNumber(phoneNumber);
    }

    private String serialAndNameKey(String serialNumber, String name) {
        return serialNumber.strip() + "\u0000" + name.strip();
    }

    private String normalizePhoneNumber(String phoneNumber) {
        return phoneNumber == null ? "" : phoneNumber.replaceAll("[^0-9]", "");
    }

    private record Layout(int headerRowIndex,
                          int serialNumberColumn,
                          int nameColumn,
                          int phoneNumberColumn,
                          int firstDateColumn) {
    }

    private record RowRange(int firstRow, int lastRow) {
        boolean contains(int rowIndex) {
            // Apache POI의 행 인덱스는 0부터, COUNTIF 수식의 행 번호는 1부터 시작한다.
            int excelRowNumber = rowIndex + 1;
            return firstRow <= excelRowNumber && excelRowNumber <= lastRow;
        }
    }

    private record DateColumn(int columnIndex, LocalDate date, RowRange formulaRange) {
        boolean includes(int rowIndex) {
            return formulaRange == null || formulaRange.contains(rowIndex);
        }
    }

    private record AttendanceKey(LocalDate date, String personKey) {
    }

    private static final class MonthAccumulator {
        private int mealCount;
        private final Set<String> people = new HashSet<>();
        private final Set<LocalDate> attendanceDates = new HashSet<>();

        private void record(LocalDate date, String personKey) {
            mealCount++;
            people.add(personKey);
            attendanceDates.add(date);
        }
    }

    private static final class PersonAccumulator {
        private String serialNumber;
        private String name;
        private int visitCount;
        private LocalDate lastAttendance;

        private PersonAccumulator(String serialNumber, String name) {
            this.serialNumber = serialNumber;
            this.name = name;
        }

        private void record(LocalDate date, String recordedSerialNumber, String recordedName) {
            visitCount++;
            if (lastAttendance == null || !date.isBefore(lastAttendance)) {
                lastAttendance = date;
                serialNumber = recordedSerialNumber;
                name = recordedName;
            }
        }
    }

    private static final class DayAccumulator {
        private int mealCount;
        private final Set<String> people = new HashSet<>();

        private void record(String personKey) {
            mealCount++;
            people.add(personKey);
        }
    }

    /**
     * 통계의 정식 식별자는 이름+생년월일이다. 기존 월별 일지는 생년월일 대신 전화번호만 갖고
     * 있으므로, 현재 명단의 이름+전화번호로 생년월일을 찾아 과거 일지를 같은 PK로 연결한다.
     */
    private final class IdentityResolver {
        private final Map<String, User> usersByNameAndPhone = new HashMap<>();
        private final Map<String, User> usersBySerialAndName = new HashMap<>();

        private IdentityResolver(List<User> users) {
            for (User user : users) {
                if (!normalizePhoneNumber(user.getPhoneNumber()).isBlank()) {
                    usersByNameAndPhone.put(nameAndPhoneKey(user.getName(), user.getPhoneNumber()), user);
                }
                usersBySerialAndName.put(serialAndNameKey(user.getSerialNumber(), user.getName()), user);
            }
        }

        private PersonIdentity resolve(String serialNumber, String name, String phoneNumber) {
            User user = null;
            if (!normalizePhoneNumber(phoneNumber).isBlank()) {
                user = usersByNameAndPhone.get(nameAndPhoneKey(name, phoneNumber));
            }
            if (user == null) user = usersBySerialAndName.get(serialAndNameKey(serialNumber, name));
            if (user != null) {
                return new PersonIdentity(primaryKey(user.getName(), user.getBirthDate()),
                        user.getSerialNumber(), user.getName());
            }

            // 현재 명단에서 이미 빠진 과거 대상자는 생년월일이 남아 있지 않다. 이 경우에는
            // 같은 이름·전화번호를 한 사람으로만 묶는 보정 키를 사용해 출석 건수를 보존한다.
            String fallbackKey = nameAndPhoneKey(name, phoneNumber);
            if (normalizePhoneNumber(phoneNumber).isBlank()) {
                fallbackKey = serialAndNameKey(serialNumber, name);
            }
            return new PersonIdentity("legacy\u0000" + fallbackKey, serialNumber, name);
        }
    }

    private record PersonIdentity(String key, String serialNumber, String name) {
    }
}
