package com.bgrc.attendance.domain.admin.controller;

import com.bgrc.attendance.domain.admin.service.AdminService;
import com.bgrc.attendance.domain.user.service.UserService;
import com.bgrc.attendance.global.common.CommonResponse;
import com.bgrc.attendance.domain.admin.dto.AdminResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;


@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {
    private final AdminService adminService;
    private final UserService userService;

    @GetMapping("/config")
    public AdminResponse getConfig(){
        return adminService.getConfig();
    }

    @PostMapping("/upload")
    public CommonResponse<AdminResponse> upload(@RequestParam("file") MultipartFile file) {
        adminService.upload(file);
        userService.init(); // 명단 로드
        return CommonResponse.success(adminService.getConfig());
    }
}
