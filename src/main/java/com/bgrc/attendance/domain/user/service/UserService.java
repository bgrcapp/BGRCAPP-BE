package com.bgrc.attendance.domain.user.service;

import com.bgrc.attendance.domain.user.config.ExcelUploadConfig;
import com.bgrc.attendance.domain.user.model.User;
import com.bgrc.attendance.domain.user.repository.UserRepository;
import com.bgrc.attendance.global.common.CustomException;
import com.bgrc.attendance.global.common.ResponseCode;
import com.bgrc.attendance.global.util.ExcelFileUtils;
import com.bgrc.attendance.global.util.RuntimeDataPathResolver;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;


@Service
@RequiredArgsConstructor
@Slf4j
public class UserService {
    private static final String ACTIVE_ROSTER_XLSX = "attendance-roster.xlsx";
    private static final String ACTIVE_ROSTER_XLS = "attendance-roster.xls";
    private static final int HEADER_SEARCH_ROWS = 20;

    private final UserRepository userRepository;
    private final ExcelUploadConfig excelUploadConfig;
    private final ExcelFileUtils excelFileUtils;
    private final RuntimeDataPathResolver runtimeDataPathResolver;
    private final DataFormatter dataFormatter = new DataFormatter();

    @PostConstruct
    public synchronized void init(){
        try {
            migrateLegacyRosterIfNecessary();
            Files.createDirectories(uploadDirectory());
            Optional<Path> rosterPath = getActiveRosterPath();
            if (rosterPath.isEmpty()) {
                userRepository.clear();
                log.warn(ResponseCode.EXCEL_FILE_NOT_FOUND.getMessage());
                return;
            }

            userRepository.replaceAll(readUsers(rosterPath.get()));
            log.info("출석 대상 명단에서 {}명의 사용자 로드 완료", userRepository.count());
        } catch (IOException e) {
            throw new CustomException(ResponseCode.EXCEL_READ_FAILED);
        }
    }

    /** 관리자 업로드 명단을 교체하고, 이전 파일은 정리한다. */
    public synchronized int replaceRoster(MultipartFile file) {
        RosterReplacement replacement = replaceRosterWithRollback(file);
        replacement.complete();
        return replacement.userCount();
    }

    /**
     * 관리자 업로드 명단을 교체한다. 호출자는 월별 일지 동기화가 성공하면 {@link RosterReplacement#complete()},
     * 실패하면 {@link RosterReplacement#rollback()}을 호출해야 한다.
     */
    public synchronized RosterReplacement replaceRosterWithRollback(MultipartFile file) {
        validateUpload(file);
        List<User> users;
        try (InputStream inputStream = file.getInputStream()) {
            users = readUsers(inputStream);
        } catch (IOException e) {
            throw new CustomException(ResponseCode.EXCEL_READ_FAILED);
        }

        if (users.isEmpty()) throw new CustomException(ResponseCode.EXCEL_READ_FAILED);

        Path directory = uploadDirectory();
        String extension = extensionOf(file.getOriginalFilename());
        Path temporaryFile = null;
        Path activePath = directory.resolve(activeFileName(extension));
        List<User> previousUsers = List.copyOf(userRepository.findAll());
        List<BackupFile> backups = new ArrayList<>();
        boolean newRosterApplied = false;
        try {
            Files.createDirectories(directory);
            List<Path> previousRosterFiles = listRosterFiles(directory);
            temporaryFile = Files.createTempFile(directory, ".roster-upload-", extension);
            file.transferTo(temporaryFile);

            backupRosterFiles(directory, previousRosterFiles, backups);
            moveAtomically(temporaryFile, activePath);
            temporaryFile = null;
            newRosterApplied = true;
            userRepository.replaceAll(users);
            log.info("출석 대상 명단 {}명 업로드 완료", userRepository.count());
            return new RosterReplacement(previousUsers, List.copyOf(users), activePath, backups);
        } catch (IOException e) {
            restoreFailedReplacement(activePath, newRosterApplied, backups, previousUsers);
            log.error("업로드 명단 저장 실패: {}", activePath, e);
            throw saveFailure(e);
        } catch (RuntimeException e) {
            restoreFailedReplacement(activePath, newRosterApplied, backups, previousUsers);
            throw e;
        } finally {
            if (temporaryFile != null) {
                try {
                    Files.deleteIfExists(temporaryFile);
                } catch (IOException ignored) {
                    log.warn("임시 명단 파일 삭제 실패: {}", temporaryFile);
                }
            }
        }
    }

    /** 기존 운영 경로에 수동 배치한 명단도 첫 실행 때 한 번 읽을 수 있게 한다. */
    public synchronized void loadUsersFromExcel() throws IOException {
        Optional<Path> rosterPath = getActiveRosterPath();
        if (rosterPath.isEmpty()) throw new CustomException(ResponseCode.EXCEL_FILE_NOT_FOUND);
        userRepository.replaceAll(readUsers(rosterPath.get()));
    }

    public List<User> getUsers() {
        return userRepository.findAll();
    }

    public boolean hasRosterFile() {
        return getActiveRosterPath().isPresent();
    }

    public String getActiveRosterFileName() {
        return getActiveRosterPath().map(path -> path.getFileName().toString()).orElse(null);
    }

    public Optional<Path> getActiveRosterPath() {
        Path directory = uploadDirectory();
        Path xlsxPath = directory.resolve(ACTIVE_ROSTER_XLSX);
        if (Files.isRegularFile(xlsxPath)) return Optional.of(xlsxPath);
        Path xlsPath = directory.resolve(ACTIVE_ROSTER_XLS);
        if (Files.isRegularFile(xlsPath)) return Optional.of(xlsPath);

        // 기존에 수동 배치한 파일이 있으면 호환용으로 단 하나만 읽는다.
        if (!Files.isDirectory(directory)) return Optional.empty();
        try (var paths = Files.list(directory)) {
            List<Path> legacyFiles = paths
                    .filter(Files::isRegularFile)
                    .filter(this::isExcelFile)
                    .sorted()
                    .toList();
            return legacyFiles.size() == 1 ? Optional.of(legacyFiles.get(0)) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private List<User> readUsers(Path path) throws IOException {
        try (InputStream inputStream = Files.newInputStream(path)) {
            return readUsers(inputStream);
        }
    }

    private List<User> readUsers(InputStream inputStream) throws IOException {
        try (Workbook workbook = WorkbookFactory.create(inputStream)) {
            List<User> users = new ArrayList<>();
            boolean foundRosterHeader = false;

            for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
                Sheet sheet = workbook.getSheetAt(sheetIndex);
                RosterLayout layout = findRosterLayout(sheet);
                if (layout == null) continue;
                foundRosterHeader = true;

                for (int rowIndex = layout.headerRowIndex() + 1;
                     rowIndex <= sheet.getLastRowNum();
                     rowIndex++) {
                    Row row = sheet.getRow(rowIndex);
                    if (row == null) continue;
                    // 연번이 없는 행은 종결 이용자이므로 출석 대상에 포함하지 않는다.
                    if (isBlank(row.getCell(layout.serialNumberColumn()))) {
                        continue;
                    }

                    String serialNumber = cellText(row.getCell(layout.serialNumberColumn()));
                    String name = cellText(row.getCell(layout.nameColumn()));
                    Cell birthCell = row.getCell(layout.birthDateColumn());
                    if (name.isBlank() || isBlank(birthCell)) continue;

                    String birthDate = excelFileUtils.formatBirthDate(birthCell);
                    String phoneNumber = layout.phoneNumberColumn() < 0
                            ? ""
                            : cellText(row.getCell(layout.phoneNumberColumn()));
                    if (!birthDate.isBlank()) users.add(new User(serialNumber, name, birthDate, phoneNumber));
                }

                // 하나의 원본 명단 시트만 사용한다. 여러 시트의 이전·종결 명단을 합치지 않는다.
                break;
            }

            if (!foundRosterHeader) throw new CustomException(ResponseCode.EXCEL_READ_FAILED);
            return users;
        }
    }

    private RosterLayout findRosterLayout(Sheet sheet) {
        for (int rowIndex = 0; rowIndex <= Math.min(HEADER_SEARCH_ROWS, sheet.getLastRowNum()); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) continue;

            int serialNumberColumn = -1;
            int nameColumn = -1;
            int birthDateColumn = -1;
            int phoneNumberColumn = -1;
            for (int columnIndex = 0; columnIndex < row.getLastCellNum(); columnIndex++) {
                String header = cellText(row.getCell(columnIndex));
                if (header.contains("연번")) serialNumberColumn = columnIndex;
                if (header.contains("성명") || header.equals("이름")) nameColumn = columnIndex;
                if (header.contains("생년월일")) birthDateColumn = columnIndex;
                if (header.contains("전화번호") || header.contains("연락처")) phoneNumberColumn = columnIndex;
            }
            if (serialNumberColumn >= 0 && nameColumn >= 0 && birthDateColumn >= 0) {
                return new RosterLayout(rowIndex, serialNumberColumn, nameColumn, birthDateColumn, phoneNumberColumn);
            }
        }
        return null;
    }

    private void validateUpload(MultipartFile file) {
        if (file == null || file.isEmpty() || file.getOriginalFilename() == null || !isExcelFile(file.getOriginalFilename())) {
            throw new CustomException(ResponseCode.INVALID_ADMIN_REQUEST);
        }
    }

    private boolean isBlank(Cell cell) {
        return cell == null
                || cell.getCellType() == CellType.BLANK
                || cellText(cell).isEmpty();
    }

    private String cellText(Cell cell) {
        return cell == null ? "" : dataFormatter.formatCellValue(cell).trim();
    }

    private boolean isExcelFile(Path path) {
        return isExcelFile(path.getFileName().toString());
    }

    private boolean isExcelFile(String filename) {
        String lowercase = filename.toLowerCase(Locale.ROOT);
        return lowercase.endsWith(".xlsx") || lowercase.endsWith(".xls");
    }

    private String extensionOf(String filename) {
        return filename.toLowerCase(Locale.ROOT).endsWith(".xls") && !filename.toLowerCase(Locale.ROOT).endsWith(".xlsx")
                ? ".xls"
                : ".xlsx";
    }

    private String activeFileName(String extension) {
        return ".xls".equals(extension) ? ACTIVE_ROSTER_XLS : ACTIVE_ROSTER_XLSX;
    }

    private Path uploadDirectory() {
        return runtimeDataPathResolver.resolve(excelUploadConfig.getUploadDir());
    }

    /** build/libs에서 실행했던 구버전의 명단을 프로젝트 data로 안전하게 이어받는다. */
    private void migrateLegacyRosterIfNecessary() throws IOException {
        Path targetDirectory = uploadDirectory();
        Files.createDirectories(targetDirectory);
        for (String fileName : List.of(ACTIVE_ROSTER_XLSX, ACTIVE_ROSTER_XLS)) {
            Path target = targetDirectory.resolve(fileName);
            if (Files.isRegularFile(target)) continue;

            Path legacy = runtimeDataPathResolver.resolveFromWorkingDirectory(excelUploadConfig.getUploadDir())
                    .resolve(fileName);
            if (Files.isRegularFile(legacy) && !legacy.equals(target)) {
                Files.copy(legacy, target);
                log.info("기존 실행 경로의 업로드 명단을 data 경로로 이전했습니다: {}", target);
            }
        }
    }

    private void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private List<Path> listRosterFiles(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) return List.of();
        try (var paths = Files.list(directory)) {
            return paths.filter(Files::isRegularFile).filter(this::isExcelFile).toList();
        }
    }

    private void backupRosterFiles(Path directory, List<Path> previousRosterFiles,
                                   List<BackupFile> backups) throws IOException {
        for (Path original : previousRosterFiles) {
            Path backup = directory.resolve("." + original.getFileName() + ".backup-" + UUID.randomUUID());
            moveAtomically(original, backup);
            backups.add(new BackupFile(original, backup));
        }
    }

    private void restoreFailedReplacement(Path activePath, boolean newRosterApplied,
                                           List<BackupFile> backups, List<User> previousUsers) {
        if (newRosterApplied) {
            try {
                Files.deleteIfExists(activePath);
            } catch (IOException e) {
                log.error("새 명단 파일 롤백 실패: {}", activePath, e);
            }
        }
        restoreBackups(backups);
        userRepository.replaceAll(previousUsers);
    }

    private void restoreBackups(List<BackupFile> backups) {
        for (BackupFile backup : backups) {
            try {
                if (Files.isRegularFile(backup.backup())) {
                    moveAtomically(backup.backup(), backup.original());
                }
            } catch (IOException e) {
                log.error("기존 명단 파일 롤백 실패: {}", backup.original(), e);
            }
        }
    }

    private void cleanupBackups(List<BackupFile> backups) {
        for (BackupFile backup : backups) {
            try {
                Files.deleteIfExists(backup.backup());
            } catch (IOException e) {
                // 새 명단은 이미 안전하게 반영된 상태이므로, 백업 정리 실패가 업로드 자체를 실패시키지는 않는다.
                log.warn("이전 명단 백업 파일 삭제 실패: {}", backup.backup(), e);
            }
        }
    }

    private CustomException saveFailure(IOException e) {
        if (isFileInUse(e)) {
            return new CustomException(ResponseCode.EXCEL_FILE_IN_USE);
        }
        if (e instanceof AccessDeniedException) {
            return new CustomException(ResponseCode.EXCEL_SAVE_ACCESS_DENIED);
        }
        return new CustomException(ResponseCode.EXCEL_SAVE_FAILED);
    }

    /** Windows의 공유 위반 메시지에만 "파일 사용 중" 안내를 사용한다. */
    private boolean isFileInUse(IOException e) {
        if (!(e instanceof FileSystemException fileSystemException)) return false;
        String detail = (String.valueOf(fileSystemException.getReason()) + " "
                + String.valueOf(fileSystemException.getMessage())).toLowerCase(Locale.ROOT);
        return detail.contains("being used by another process")
                || detail.contains("used by another process")
                || detail.contains("sharing violation")
                || (detail.contains("다른 프로세스") && detail.contains("사용"));
    }

    public Boolean findUser(String name, String birthDate){
        return userRepository.findByNameAndBirthDate(name, birthDate);
    }

    public Optional<User> findRegisteredUser(String name, String birthDate) {
        return userRepository.findUser(name, birthDate);
    }

    public int getUserCount(){
        return userRepository.count();
    }

    public final class RosterReplacement {
        private final List<User> previousUsers;
        private final List<User> users;
        private final Path activePath;
        private final List<BackupFile> backups;
        private boolean finished;

        private RosterReplacement(List<User> previousUsers, List<User> users,
                                  Path activePath, List<BackupFile> backups) {
            this.previousUsers = previousUsers;
            this.users = users;
            this.activePath = activePath;
            this.backups = List.copyOf(backups);
        }

        public List<User> users() {
            return users;
        }

        public int userCount() {
            return users.size();
        }

        /** 월별 일지까지 정상 반영된 후 이전 명단 백업을 정리한다. */
        public void complete() {
            synchronized (UserService.this) {
                if (finished) return;
                cleanupBackups(backups);
                finished = true;
            }
        }

        /** 월별 일지 반영에 실패하면 활성 명단과 메모리 명단을 이전 상태로 되돌린다. */
        public void rollback() {
            synchronized (UserService.this) {
                if (finished) return;
                restoreFailedReplacement(activePath, true, backups, previousUsers);
                finished = true;
            }
        }
    }

    private record RosterLayout(int headerRowIndex,
                                int serialNumberColumn,
                                int nameColumn,
                                int birthDateColumn,
                                int phoneNumberColumn) {
    }

    private record BackupFile(Path original, Path backup) {
    }
}
