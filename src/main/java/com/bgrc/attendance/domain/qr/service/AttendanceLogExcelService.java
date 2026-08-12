package com.bgrc.attendance.domain.qr.service;

import com.bgrc.attendance.domain.qr.config.AttendanceLogConfig;
import com.bgrc.attendance.domain.user.model.User;
import com.bgrc.attendance.global.common.CustomException;
import com.bgrc.attendance.global.common.ResponseCode;
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
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class AttendanceLogExcelService {
    private static final int HEADER_SEARCH_ROWS = 10;
    private static final int ATTENDANCE_DATA_START_OFFSET = 1;
    private static final String ATTENDANCE_MARK = "o";

    private final AttendanceLogConfig attendanceLogConfig;
    private final RuntimeDataPathResolver runtimeDataPathResolver;

    private final DataFormatter dataFormatter = new DataFormatter();
    private final Map<String, List<AttendanceTarget>> targetsByName = new HashMap<>();
    private LocalDate initializedDate;

    public synchronized void initialize() {
        initialize(LocalDate.now());
    }

    public synchronized void initialize(LocalDate date) {
        targetsByName.clear();
        initializedDate = date;
        Path workbookPath = getWorkbookPath(date);
        if (!Files.exists(workbookPath)) {
            log.warn("출석 로그 Excel 파일을 찾을 수 없습니다: {}", workbookPath.toAbsolutePath());
            return;
        }

        try (InputStream inputStream = Files.newInputStream(workbookPath);
             Workbook workbook = new XSSFWorkbook(inputStream)) {
            for (String sheetName : getConfiguredSheetNames()) {
                Sheet sheet = workbook.getSheet(sheetName);
                if (sheet == null) {
                    log.warn("출석 로그 시트를 찾을 수 없습니다: {}", sheetName);
                    continue;
                }
                Layout layout = findLayout(sheet);
                if (layout == null) {
                    log.warn("출석 로그 시트의 헤더를 찾을 수 없습니다: {}", sheetName);
                    continue;
                }
                loadTargets(sheet, layout);
            }
            log.debug("출석 로그 대상자 {}명 로드 완료", targetsByName.values().stream()
                    .mapToInt(List::size)
                    .sum());
        } catch (IOException e) {
            throw new CustomException(ResponseCode.ATTENDANCE_LOG_READ_FAILED);
        }
    }

    public synchronized void ensureInitialized(LocalDate date) {
        if (!date.equals(initializedDate)) initialize(date);
    }

    /** 선택한 날짜에 출석 일지에 이미 표시된 대상자 키를 읽는다. */
    public synchronized Set<String> loadMarkedKeys(LocalDate date) {
        Path workbookPath = getWorkbookPath(date);
        if (!Files.exists(workbookPath)) return Collections.emptySet();

        Set<String> markedKeys = new HashSet<>();
        try (InputStream inputStream = Files.newInputStream(workbookPath);
             Workbook workbook = new XSSFWorkbook(inputStream)) {
            for (String sheetName : getConfiguredSheetNames()) {
                Sheet sheet = workbook.getSheet(sheetName);
                if (sheet == null) continue;
                Layout layout = findLayout(sheet);
                if (layout == null) continue;
                int dateColumn = findDateColumn(sheet, layout.headerRowIndex(), date);
                if (dateColumn < 0) continue;

                for (int rowIndex = layout.headerRowIndex() + ATTENDANCE_DATA_START_OFFSET;
                     rowIndex <= sheet.getLastRowNum();
                     rowIndex++) {
                    Row row = sheet.getRow(rowIndex);
                    AttendanceTarget target = toTarget(sheet, row, layout, rowIndex);
                    if (target == null) continue;
                    if (isMarked(row.getCell(dateColumn))) markedKeys.add(target.key());
                }
            }
            return markedKeys;
        } catch (IOException e) {
            throw new CustomException(ResponseCode.ATTENDANCE_LOG_READ_FAILED);
        }
    }

    /** 기존 호출부 호환용 이름이다. 실제 기준일은 호출자가 전달한 date다. */
    public synchronized Set<String> loadTodayMarkedKeys(LocalDate date) {
        return loadMarkedKeys(date);
    }

    public synchronized List<AttendanceStatus> getTodayAttendance(LocalDate date) {
        if (!Files.exists(getWorkbookPath(date))) {
            throw new CustomException(ResponseCode.ATTENDANCE_LOG_FILE_NOT_FOUND);
        }

        Set<String> markedKeys = loadMarkedKeys(date);
        return targetsByName.values().stream()
                .flatMap(List::stream)
                .sorted(Comparator
                        .comparing(AttendanceTarget::sheetName)
                        .thenComparingInt(target -> serialSortValue(target.serialNumber())))
                .map(target -> new AttendanceStatus(target, markedKeys.contains(target.key())))
                .toList();
    }

    public synchronized Optional<AttendanceTarget> findUniqueTarget(String name) {
        List<AttendanceTarget> targets = targetsByName.getOrDefault(normalize(name), List.of());
        if (targets.size() == 1) return Optional.of(targets.get(0));
        return Optional.empty();
    }

    public synchronized Optional<AttendanceTarget> findTarget(User user) {
        return targetsByName.getOrDefault(normalize(user.getName()), List.of()).stream()
                .filter(target -> target.serialNumber().equals(normalize(user.getSerialNumber())))
                .findFirst();
    }

    public synchronized MarkResult markAttendance(String name, LocalDate date) {
        AttendanceTarget cachedTarget = findUniqueTarget(name).orElse(null);
        if (cachedTarget == null) return MarkResult.targetNotFound();

        return markAttendance(cachedTarget, date);
    }

    public synchronized MarkResult markAttendance(User user, LocalDate date) {
        AttendanceTarget cachedTarget = findTarget(user).orElse(null);
        if (cachedTarget == null) return MarkResult.targetNotFound();

        return markAttendance(cachedTarget, date);
    }

    /** 관리자가 선택한 출석 상태를 반대로 바꾸고, 변경 후 상태를 반환한다. */
    public synchronized boolean toggleAttendance(User user, LocalDate date) {
        AttendanceTarget cachedTarget = findTarget(user)
                .orElseThrow(() -> new CustomException(ResponseCode.ATTENDANCE_LOG_TARGET_NOT_FOUND));
        Path workbookPath = getWorkbookPath(date);
        if (!Files.exists(workbookPath)) {
            throw new CustomException(ResponseCode.ATTENDANCE_LOG_FILE_NOT_FOUND);
        }

        Path tempPath = null;
        try (InputStream inputStream = Files.newInputStream(workbookPath);
             Workbook workbook = new XSSFWorkbook(inputStream)) {
            Sheet sheet = workbook.getSheet(cachedTarget.sheetName());
            if (sheet == null) throw new CustomException(ResponseCode.ATTENDANCE_LOG_TARGET_NOT_FOUND);

            Layout layout = findLayout(sheet);
            if (layout == null) throw new CustomException(ResponseCode.ATTENDANCE_LOG_TARGET_NOT_FOUND);

            int dateColumn = findDateColumn(sheet, layout.headerRowIndex(), date);
            if (dateColumn < 0) throw new CustomException(ResponseCode.ATTENDANCE_LOG_DATE_NOT_FOUND);

            Row row = sheet.getRow(cachedTarget.rowIndex());
            AttendanceTarget target = toTarget(sheet, row, layout, cachedTarget.rowIndex());
            if (target == null || !target.key().equals(cachedTarget.key())) {
                throw new CustomException(ResponseCode.ATTENDANCE_LOG_TARGET_NOT_FOUND);
            }

            Cell attendanceCell = row.getCell(dateColumn);
            boolean attended = !isMarked(attendanceCell);
            if (attendanceCell == null) attendanceCell = row.createCell(dateColumn);
            if (attended) {
                attendanceCell.setCellValue(ATTENDANCE_MARK);
            } else {
                attendanceCell.setBlank();
            }

            tempPath = Files.createTempFile(workbookPath.getParent(), workbookPath.getFileName().toString(), ".tmp");
            try (OutputStream outputStream = Files.newOutputStream(tempPath)) {
                workbook.write(outputStream);
            }
            moveAtomically(tempPath, workbookPath);
            tempPath = null;
            log.info("관리자 출석 상태 변경: {} / {} / {}", date, user.getName(), attended ? "출석" : "결석");
            return attended;
        } catch (IOException e) {
            throw new CustomException(ResponseCode.ATTENDANCE_LOG_WRITE_FAILED);
        } finally {
            if (tempPath != null) {
                try {
                    Files.deleteIfExists(tempPath);
                } catch (IOException cleanupException) {
                    log.warn("관리자 출석 변경 임시 파일 삭제 실패: {}", tempPath, cleanupException);
                }
            }
        }
    }

    private MarkResult markAttendance(AttendanceTarget cachedTarget, LocalDate date) {
        long startedAt = System.nanoTime();
        Path workbookPath = getWorkbookPath(date);
        if (!Files.exists(workbookPath)) {
            throw new CustomException(ResponseCode.ATTENDANCE_LOG_FILE_NOT_FOUND);
        }

        Path tempPath = null;
        try (InputStream inputStream = Files.newInputStream(workbookPath);
             Workbook workbook = new XSSFWorkbook(inputStream)) {
            Sheet sheet = workbook.getSheet(cachedTarget.sheetName());
            if (sheet == null) return MarkResult.targetNotFound();

            Layout layout = findLayout(sheet);
            if (layout == null) return MarkResult.targetNotFound();

            int dateColumn = findDateColumn(sheet, layout.headerRowIndex(), date);
            if (dateColumn < 0) return MarkResult.dateNotFound();

            Row row = sheet.getRow(cachedTarget.rowIndex());
            AttendanceTarget target = toTarget(sheet, row, layout, cachedTarget.rowIndex());
            if (target == null) return MarkResult.targetNotFound();

            Cell attendanceCell = row.getCell(dateColumn);
            if (isMarked(attendanceCell)) return MarkResult.alreadyMarked(target);

            if (attendanceCell == null) attendanceCell = row.createCell(dateColumn);
            attendanceCell.setCellValue(ATTENDANCE_MARK);

            tempPath = Files.createTempFile(workbookPath.getParent(), workbookPath.getFileName().toString(), ".tmp");
            try (OutputStream outputStream = Files.newOutputStream(tempPath)) {
                workbook.write(outputStream);
            }
            moveAtomically(tempPath, workbookPath);
            tempPath = null;
            log.info("출석 일지 Excel 저장 시간: {} ms ({})",
                    (System.nanoTime() - startedAt) / 1_000_000, workbookPath);
            return MarkResult.recorded(target);
        } catch (IOException e) {
            throw new CustomException(ResponseCode.ATTENDANCE_LOG_WRITE_FAILED);
        } finally {
            if (tempPath != null) {
                try {
                    Files.deleteIfExists(tempPath);
                } catch (IOException cleanupException) {
                    log.warn("임시 출석 로그 파일 삭제 실패: {}", tempPath, cleanupException);
                }
            }
        }
    }

    private void loadTargets(Sheet sheet, Layout layout) {
        for (int rowIndex = layout.headerRowIndex() + ATTENDANCE_DATA_START_OFFSET;
             rowIndex <= sheet.getLastRowNum();
             rowIndex++) {
            AttendanceTarget target = toTarget(sheet, sheet.getRow(rowIndex), layout, rowIndex);
            if (target == null) continue;
            targetsByName.computeIfAbsent(normalize(target.name()), ignored -> new ArrayList<>()).add(target);
        }
    }

    private AttendanceTarget toTarget(Sheet sheet, Row row, Layout layout, int rowIndex) {
        if (row == null) return null;
        String serial = cellText(row.getCell(layout.serialNumberColumn()));
        String name = cellText(row.getCell(layout.nameColumn()));
        if (serial.isBlank() || name.isBlank()) return null;
        return new AttendanceTarget(
                sheet.getSheetName() + ":" + serial,
                sheet.getSheetName(),
                rowIndex,
                serial,
                name);
    }

    private Layout findLayout(Sheet sheet) {
        for (int rowIndex = 0; rowIndex <= Math.min(HEADER_SEARCH_ROWS, sheet.getLastRowNum()); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) continue;

            int serialNumberColumn = -1;
            int nameColumn = -1;
            for (int columnIndex = 0; columnIndex < row.getLastCellNum(); columnIndex++) {
                String value = cellText(row.getCell(columnIndex));
                if (value.contains("연번")) serialNumberColumn = columnIndex;
                if (value.equals("성명") || value.contains("성명")) nameColumn = columnIndex;
            }
            if (serialNumberColumn >= 0 && nameColumn >= 0) {
                return new Layout(rowIndex, serialNumberColumn, nameColumn);
            }
        }
        return null;
    }

    private int findDateColumn(Sheet sheet, int headerRowIndex, LocalDate date) {
        Row headerRow = sheet.getRow(headerRowIndex);
        if (headerRow == null) return -1;

        for (int columnIndex = 0; columnIndex < headerRow.getLastCellNum(); columnIndex++) {
            Cell cell = headerRow.getCell(columnIndex);
            if (cell == null) continue;
            LocalDate cellDate = toLocalDate(cell);
            if (date.equals(cellDate) || matchesDisplayedDate(cell, date)) return columnIndex;
        }
        return -1;
    }

    private LocalDate toLocalDate(Cell cell) {
        if (cell == null) return null;
        if (cell.getCellType() == CellType.NUMERIC && DateUtil.isValidExcelDate(cell.getNumericCellValue())) {
            return DateUtil.getJavaDate(cell.getNumericCellValue())
                    .toInstant()
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate();
        }
        return null;
    }

    /** 양식의 날짜 헤더가 문자로 표현된 경우도 읽을 수 있도록 지원한다. */
    private boolean matchesDisplayedDate(Cell cell, LocalDate expectedDate) {
        String text = cellText(cell)
                .replaceAll("\\s", "")
                .replace('.', '/')
                .replace('-', '/');
        String monthDay = "%d/%d".formatted(expectedDate.getMonthValue(), expectedDate.getDayOfMonth());
        if (monthDay.equals(text) || (expectedDate.getMonthValue() + "월" + expectedDate.getDayOfMonth() + "일").equals(text)) {
            return true;
        }

        try {
            return expectedDate.equals(LocalDate.parse(text, DateTimeFormatter.ofPattern("uuuu/M/d")));
        } catch (DateTimeParseException ignored) {
            return false;
        }
    }

    private boolean isMarked(Cell cell) {
        return cell != null && ATTENDANCE_MARK.equalsIgnoreCase(cellText(cell));
    }

    private String cellText(Cell cell) {
        if (cell == null) return "";
        return dataFormatter.formatCellValue(cell).trim();
    }

    private String normalize(String value) {
        return value == null ? "" : value.strip();
    }

    private int serialSortValue(String serialNumber) {
        try {
            return Integer.parseInt(serialNumber);
        } catch (NumberFormatException ignored) {
            return Integer.MAX_VALUE;
        }
    }

    private List<String> getConfiguredSheetNames() {
        return List.of(attendanceLogConfig.getSheetNames().split(","))
                .stream()
                .map(String::strip)
                .filter(name -> !name.isBlank())
                .toList();
    }

    private Path getWorkbookPath(LocalDate date) {
        String directory = attendanceLogConfig.getMonthlyDir();
        Path monthlyDirectory = directory == null || directory.isBlank()
                ? runtimeDataPathResolver.resolve(attendanceLogConfig.getLogDir()).resolve("monthly")
                : runtimeDataPathResolver.resolve(directory);
        return monthlyDirectory.resolve("무료급식 일일 식사내역_%02d.%d_일지.xlsx"
                .formatted(date.getYear() % 100, date.getMonthValue()));
    }

    private void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public record AttendanceTarget(
            String key,
            String sheetName,
            int rowIndex,
            String serialNumber,
            String name) {
    }

    public enum MarkStatus {
        RECORDED,
        ALREADY_MARKED,
        TARGET_NOT_FOUND,
        DATE_NOT_FOUND
    }

    public record MarkResult(MarkStatus status, AttendanceTarget target) {
        static MarkResult recorded(AttendanceTarget target) {
            return new MarkResult(MarkStatus.RECORDED, target);
        }

        static MarkResult alreadyMarked(AttendanceTarget target) {
            return new MarkResult(MarkStatus.ALREADY_MARKED, target);
        }

        static MarkResult targetNotFound() {
            return new MarkResult(MarkStatus.TARGET_NOT_FOUND, null);
        }

        static MarkResult dateNotFound() {
            return new MarkResult(MarkStatus.DATE_NOT_FOUND, null);
        }
    }

    public record AttendanceStatus(AttendanceTarget target, boolean attended) {
    }

    private record Layout(int headerRowIndex, int serialNumberColumn, int nameColumn) {
    }
}
