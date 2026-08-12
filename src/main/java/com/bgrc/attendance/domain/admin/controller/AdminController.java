package com.bgrc.attendance.domain.admin.controller;

import com.bgrc.attendance.domain.admin.service.AdminService;
import com.bgrc.attendance.global.common.CommonResponse;
import com.bgrc.attendance.domain.admin.dto.AdminResponse;
import com.bgrc.attendance.domain.admin.dto.AttendanceStatusResponse;
import com.bgrc.attendance.global.common.CustomException;
import com.bgrc.attendance.global.common.ResponseCode;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {
    private final AdminService adminService;

    @GetMapping("/config")
    public AdminResponse getConfig(){
        return adminService.getConfig();
    }

    @GetMapping("/attendance")
    public CommonResponse<AttendanceStatusResponse> getAttendance(
            @RequestParam(value = "date", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return CommonResponse.success(adminService.getAttendance(date == null ? LocalDate.now() : date));
    }

    /** 이전 관리자 페이지 경로도 호환한다. */
    @GetMapping("/attendance/today")
    public CommonResponse<AttendanceStatusResponse> getTodayAttendance() {
        return getAttendance(LocalDate.now());
    }

    @PostMapping("/attendance/toggle")
    public CommonResponse<AttendanceStatusResponse> toggleAttendance(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam String serialNumber) {
        return CommonResponse.success(adminService.toggleAttendance(date, serialNumber));
    }

    @GetMapping("/attendance/export")
    public ResponseEntity<InputStreamResource> exportAttendanceLedger(
            @RequestParam(value = "date", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        Path ledgerPath = adminService.getAttendanceLedgerPath(date == null ? LocalDate.now() : date);
        try {
            String fileName = ledgerPath.getFileName().toString();
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .contentLength(Files.size(ledgerPath))
                    .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                            .filename(fileName, StandardCharsets.UTF_8)
                            .build()
                            .toString())
                    .body(new InputStreamResource(Files.newInputStream(ledgerPath)));
        } catch (IOException e) {
            throw new CustomException(ResponseCode.ATTENDANCE_LOG_READ_FAILED);
        }
    }

    @PostMapping({"/roster", "/upload"})
    public CommonResponse<AdminResponse> uploadRoster(@RequestParam("file") MultipartFile file) {
        adminService.uploadRoster(file);
        return CommonResponse.success(adminService.getConfig());
    }
}
