package com.bgrc.attendance.global.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice // 전역 예외 설정 annotation
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(CustomException.class)
    public CommonResponse<Void> handleCustomException(CustomException e){
        log.warn("서버 응답 : {}", e.getStatus().getMessage());
        return CommonResponse.failed(e.getStatus());
    }

    @ExceptionHandler(Exception.class)
    public CommonResponse<Void> handleException(Exception e){
        log.error("서버 응답 : 예상치 못한 오류가 발생했습니다.", e); // 여기서 e는 stackTrace 알려줌
        return CommonResponse.failed(ResponseCode.INTERNAL_SERVER_ERROR);
    }

}
