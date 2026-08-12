package com.bgrc.attendance.global.common;

import lombok.Getter;

@Getter
public enum ResponseCode {
    /*
       1000~ : 성공
    */
        ATTENDANCE_CHECK_SUCCESS(1000, true, "출석 체크가 완료되었습니다."),
        ATTENDANCE_LOAD_SUCCESS(1001, true, "출석 명단 파일의 경로를 설정했습니다."),
        ATTENDANCE_STATUS_LOAD_SUCCESS(1002, true, "오늘 출석 현황을 불러왔습니다."),
    /*
        2000~ : 요청 오류 (잘못된 입력)
    */
        INVALID_INPUT(2000, false, "잘못된 입력 값입니다."),
        INVALID_DATE_RANGE(2001, false, "잘못된 날짜 범위입니다."),
        INVALID_EXCEL_PATH(2002, false, "잘못된 엑셀 파일 경로입니다."),

    /*
        3000~ : 비즈니스 로직 오류
    */
        // 3000~ : 사용자 관련 오류
        INVALID_USER_INFO(3000, false, "등록되지 않은 사용자입니다."),

        // 3100~ : QR 코드 관련 오류
        INVALID_QR_CODE(3100, false, "유효하지 않은 QR 코드입니다."),
        INVALID_ISSUER(3101, false, "유효하지 않은 발급처입니다."),

        // 3200~ : 출석 관련 오류
        ALREADY_CHECKED_IN(3200, false, "이미 출석 체크되었습니다."),
        FILE_WRITE_FAILED(3201, false, "출석 로그 파일 생성을 실패했습니다."),
        ATTENDANCE_LOG_READ_FAILED(3202, false, "출석 일지 파일 읽기에 실패했습니다."),
        ATTENDANCE_LOG_WRITE_FAILED(3203, false, "출석 일지 파일 저장에 실패했습니다."),
        ATTENDANCE_LOG_FILE_NOT_FOUND(3204, false, "출석 일지 파일을 찾을 수 없습니다."),
        ATTENDANCE_LOG_TARGET_NOT_FOUND(3205, false, "출석 일지에서 대상자를 찾을 수 없습니다."),
        ATTENDANCE_LOG_DATE_NOT_FOUND(3206, false, "출석 일지에서 오늘 날짜를 찾을 수 없습니다."),

        // 3300~ : 엑셀/관리자 관련 오류
        NO_ATTENDANCE_DATA(3301, false, "출석 데이터가 없습니다."),
        INVALID_ADMIN_REQUEST(3302, false, "잘못된 관리자 요청입니다."),
        EXCEL_FILE_NOT_FOUND(3303, false, "엑셀 파일을 찾을 수 없습니다.\n" +
                "관리자 페이지에서 명단을 업로드 해주세요."),
        EXCEL_SAVE_FAILED(3304, false, "엑셀 파일 저장에 실패했습니다."),
        EXCEL_READ_FAILED(3305, false, "엑셀 파일 읽기에 실패했습니다."),
        EXCEL_FILE_IN_USE(3306, false, "기존 명단 파일이 열려 있거나 사용 중입니다. Excel을 닫고 다시 시도해주세요."),
        EXCEL_SAVE_ACCESS_DENIED(3307, false, "명단 저장 권한이 없습니다. 서버 실행 폴더의 data\\userlist 폴더 권한을 확인해주세요."),
    /*
        9000~ : 서버 관련 오류
    */
        INTERNAL_SERVER_ERROR(9999, false, "서버 내부 오류가 발생했습니다.");

    private final Integer code;
    private final Boolean success;
    private final String message;

    // enum 생성자는 private => 미리 정의된 값으로만 사용하기 위함
    ResponseCode(Integer code, Boolean success, String message) {
        this.code = code;
        this.success = success;
        this.message = message;
    }
}
