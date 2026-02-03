package com.bgrc.attendance.global.common;

import com.bgrc.attendance.domain.admin.dto.AdminResponse;
import com.bgrc.attendance.domain.qr.dto.QrScanResponse;
import lombok.Data;

@Data
public class CommonResponse<T> {
    private Integer code;
    private Boolean success;
    private String message;
    private T data;

    // 요청 성공 시 => DATA 전달
    public CommonResponse(ResponseCode status, T data){
        this.code = status.getCode();
        this.success = status.getSuccess();
        this.message = status.getMessage();
        this.data = data;
    }

    // 요청 실패 시
    public CommonResponse(ResponseCode status) {
        this.code = status.getCode();
        this.success = status.getSuccess();
        this.message = status.getMessage();
        this.data = null;
    }

    // 정적 팩토리 메서드 주입
    public static <T> CommonResponse<T> success(T data){
        if (data instanceof QrScanResponse) return new CommonResponse<>(ResponseCode.ATTENDANCE_CHECK_SUCCESS, data);
        if (data instanceof AdminResponse) return new CommonResponse<>(ResponseCode.ATTENDANCE_LOAD_SUCCESS, data);
        return null;
    }

    public static <T> CommonResponse<T> failed(ResponseCode status){
        return new CommonResponse<>(status);
    }
}
