package com.bgrc.attendance.domain.user.service;

import com.bgrc.attendance.domain.user.config.ExcelUploadConfig;
import com.bgrc.attendance.domain.user.model.User;
import com.bgrc.attendance.domain.user.repository.UserRepository;
import com.bgrc.attendance.global.common.CustomException;
import com.bgrc.attendance.global.common.ResponseCode;
import com.bgrc.attendance.global.util.ExcelFileUtils;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;


@Service
@RequiredArgsConstructor
@Slf4j
public class UserService {
    private final UserRepository userRepository;
    private final ExcelUploadConfig excelUploadConfig;
    private final ExcelFileUtils excelFileUtils;

    @PostConstruct
    public void init(){
        try {
            // 디렉토리 존재 여부 검사
            excelFileUtils.ensureDirectory(excelUploadConfig.getUploadDir());

            // 엑셀 파일 존재 여부 검사
            boolean fileExists = excelFileUtils.isExcelExists(excelUploadConfig.getUploadDir());
            if (!fileExists) {
                log.warn(ResponseCode.EXCEL_FILE_NOT_FOUND.getMessage());
                initMockData();
                return;
            }
            // 명단 데이터 파싱
             loadUsersFromExcel();
        } catch (IOException e) {
            throw new CustomException(ResponseCode.EXCEL_READ_FAILED);
        }
    }

    /**
     * 테스트용 Mock 데이터를 초기화합니다.
     */
    public void initMockData(){
        userRepository.clear();
        userRepository.save(new User("홍길동", "1990-01-15"));
        userRepository.save(new User("김철수", "1985-03-22"));
        userRepository.save(new User("이영희", "1992-07-08"));
        userRepository.save(new User("박민수", "1988-11-30"));
        userRepository.save(new User("최지혜", "1995-05-17"));
    }

    public void loadUsersFromExcel() throws IOException {
        File file = excelFileUtils.getExcelFile();

        try (FileInputStream fis = new FileInputStream(file);
             Workbook workbook = new XSSFWorkbook(fis)) { // 엑셀 파일 열기
            Sheet sheet = workbook.getSheetAt(0); // 첫 번째 시트

            // 기본값 -1로 설정 (못 찾음을 의미)
            int nameCol = -1;
            int birthCol = -1;
            int headerRowIdx = -1;

            // 헤더 찾기 (첫 10행 탐색)
            // 10행보다 작을 수 있으므로 Math.min 도입
            for (int r = 0; r <= Math.min(10, sheet.getLastRowNum()); r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue; // 한 번도 작성하지 않은 셀은 null

                for (int c = 0; c < row.getLastCellNum(); c++) {
                    Cell cell = row.getCell(c);
                    if (cell == null) continue;

                    String cellValue = cell.toString().trim();

                    if (cellValue.contains("성명")){
                        nameCol = c;
                        headerRowIdx = r;
                    }
                    if (cellValue.contains("생년월일")){
                        birthCol = c;
                    }
                }
                if (nameCol != -1 && birthCol != -1) break;
            }
            if (nameCol == -1 || birthCol == -1) {
                throw new CustomException(ResponseCode.EXCEL_READ_FAILED);
            }

            // 데이터 추출
            for (int r = headerRowIdx + 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;

                Cell nameCell = row.getCell(nameCol);
                Cell birthCell = row.getCell(birthCol);

                if (nameCell != null && birthCell != null) {
                    String name = nameCell.toString().trim();
                    String birthStr = excelFileUtils.formatBirthDate(birthCell);
                    userRepository.save(new User(name, birthStr));
                }
            }
            log.info("Excel에서 {}명의 사용자 로드 완료", userRepository.count());

        } catch (Exception e) {
            log.error("Excel 파일 읽기 실패: {}", e.getMessage());
            throw e;
        }
    }

    public Boolean findUser(String name, String birthDate){
        return userRepository.findByNameAndBirthDate(name, birthDate);
    }

    public int getUserCount(){
        return userRepository.count();
    }
}
