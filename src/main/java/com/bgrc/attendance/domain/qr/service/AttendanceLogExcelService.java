package com.bgrc.attendance.domain.qr.service;

import com.bgrc.attendance.domain.qr.config.AttendanceLogConfig;
import com.bgrc.attendance.global.common.CustomException;
import com.bgrc.attendance.global.common.ResponseCode;
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

    private final DataFormatter dataFormatter = new DataFormatter();
    private final Map<String, List<AttendanceTarget>> targetsByName = new HashMap<>();

    public synchronized void initialize() {
        targetsByName.clear();
        Path workbookPath = getWorkbookPath();
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
            log.info("출석 로그 대상자 {}명 로드 완료", targetsByName.values().stream()
                    .mapToInt(List::size)
                    .sum());
        } catch (IOException e) {
            throw new CustomException(ResponseCode.ATTENDANCE_LOG_READ_FAILED);
        }
    }

    public synchronized Set<String> loadTodayMarkedKeys(LocalDate date) {
        if (!Files.exists(getWorkbookPath())) return Collections.emptySet();

        Set<String> markedKeys = new HashSet<>();
        try (InputStream inputStream = Files.newInputStream(getWorkbookPath());
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

    public synchronized List<AttendanceStatus> getTodayAttendance(LocalDate date) {
        if (!Files.exists(getWorkbookPath())) {
            throw new CustomException(ResponseCode.ATTENDANCE_LOG_FILE_NOT_FOUND);
        }

        Set<String> markedKeys = loadTodayMarkedKeys(date);
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

    public synchronized MarkResult markAttendance(String name, LocalDate date) {
        AttendanceTarget cachedTarget = findUniqueTarget(name).orElse(null);
        if (cachedTarget == null) return MarkResult.targetNotFound();

        Path workbookPath = getWorkbookPath();
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
            if (date.equals(cellDate)) return columnIndex;
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

    private Path getWorkbookPath() {
        return Path.of(attendanceLogConfig.getExcelPath());
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
