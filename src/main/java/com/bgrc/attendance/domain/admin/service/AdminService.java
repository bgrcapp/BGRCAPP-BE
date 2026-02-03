package com.bgrc.attendance.domain.admin.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.bgrc.attendance.domain.user.config.ExcelUploadConfig;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.bgrc.attendance.domain.admin.dto.AdminResponse;
import com.bgrc.attendance.domain.user.service.UserService;
import com.bgrc.attendance.global.common.CustomException;
import com.bgrc.attendance.global.common.ResponseCode;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AdminService {
    private final ExcelUploadConfig excelUploadConfig;
    private final UserService userService;

    /**
     * 현재 설정된 엑셀 경로와 사용자 정보를 조회합니다.
     * @return 현재 설정 정보
     */
    public synchronized AdminResponse getConfig(){
        String excelPath = excelUploadConfig.getUploadDir();
        return AdminResponse.builder()
                .userCount(userService.getUserCount())
                .excelPath(excelPath.isEmpty() ? "설정된 경로가 없습니다" : excelPath)
                .fileExists(excelPath.isEmpty() ? false : Files.exists(Path.of(excelPath))) // Path.of("")는 현재 디렉토리를 나타냄
                .build();
    }

    /**
     * 관리자 페이지로부터 업로드된 파일을 서버 로컬 경로에 저장합니다. <br>
     * yml에 설정된 경로에 업로드된 파일명으로 저장됩니다.
     * @param file
     * @return 현재 설정 정보
     */
    public synchronized void upload(MultipartFile file){
        try {
            if (file.getOriginalFilename() == null || file.isEmpty()) throw new CustomException(ResponseCode.EXCEL_SAVE_FAILED);
            // 디렉토리 및 파일 생성
            Path directoryPath = Path.of(excelUploadConfig.getUploadDir());
            Files.createDirectories(directoryPath);
            Path savePath = directoryPath.resolve(file.getOriginalFilename());
            file.transferTo(savePath);
        } catch (IOException e) {
            throw new CustomException(ResponseCode.EXCEL_SAVE_FAILED);
        }
    }
}
