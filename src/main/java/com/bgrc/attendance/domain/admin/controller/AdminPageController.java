package com.bgrc.attendance.domain.admin.controller;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseStatus;

/** 서버 주소로 바로 접속해도 관리자 화면을 열도록 한다. */
@Controller
public class AdminPageController {
    @GetMapping("/")
    public String adminPage() {
        return "forward:/admin.html";
    }

    /** 이전 브라우저 캐시 등이 favicon을 요청해도 WAS 오류 응답으로 처리하지 않는다. */
    @GetMapping("/favicon.ico")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void favicon() {
    }
}
