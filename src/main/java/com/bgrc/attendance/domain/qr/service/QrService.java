package com.bgrc.attendance.domain.qr.service;

import com.bgrc.attendance.domain.user.service.UserService;
import com.bgrc.attendance.global.common.CustomException;
import com.bgrc.attendance.global.common.ResponseCode;
import com.bgrc.attendance.domain.qr.dto.QrScanRequest;
import com.bgrc.attendance.domain.qr.dto.QrScanResponse;
import com.bgrc.attendance.domain.user.dto.UserInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Service
@RequiredArgsConstructor
@Slf4j
public class QrService {
    private final AttendanceService attendanceService;
    private final UserService userService;

    private record ParsedQrData(String name, String birthDate, String issuer){}; // record 사용을 위한 선언
    private static final String VALID_ISSUER = "북구장애인종합복지관";

    /**
     * {@code request}로 넘어온 QR 데이터 값을 받고 분석하여 출석을 관리합니다. <br>
     * @param request
     * @return 승인 결과 ({@code Success}, {@code Message}, {@code UserInfo}) 반환
     */
    public QrScanResponse scan(QrScanRequest request) {
        ParsedQrData data = parseQr(request.getQrData());
        log.info("출석 요청 : {}", request.getQrData());
        log.info("요청일시 : {}", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));

        // QR 데이터 검증 - 정상 데이터 검증
        if (data == null) throw new CustomException(ResponseCode.INVALID_QR_CODE);

        // QR 데이터 검증 - 발급처 검증
        if (!VALID_ISSUER.equals(data.issuer())) throw new CustomException(ResponseCode.INVALID_ISSUER);

        // 명단 내 존재 여부 확인
        Boolean isExists = userService.findUser(data.name(), data.birthDate());
        if (!isExists) throw new CustomException(ResponseCode.INVALID_USER_INFO);

        // 중복 출석 확인
        if (attendanceService.isAttended(data.name(), data.birthDate())) throw new CustomException(ResponseCode.ALREADY_CHECKED_IN);

        // 출석 로그 파일 생성
        attendanceService.createLog(data.name(), data.birthDate());

        // 성공 응답을 위한 UserInfo 객체 생성
        UserInfo userInfo = UserInfo.builder()
                .name(data.name())
                .birthDate(data.birthDate())
                .inRegistry(true)
                .build();

        log.info("서버 응답 : 출석 체크가 완료되었습니다.");

        return QrScanResponse.builder()
                .userInfo(userInfo)
                .welcomeMessage(data.name()+"님\n반갑습니다")
                .build();
    }

    /**
     * Request로 날아온 JSON 형태의 QR 데이터 값을 끊어서 반환합니다.
     * @param qrData Request로 날아온 JSON 형태의 값
     */
    private ParsedQrData parseQr(String qrData){
        try {
            String[] data = qrData.split("/");
            return new ParsedQrData(
                    data[0].strip(), // name
                    data[1].strip(), // birthDate
                    data[2].strip()  // issuer
            );
        } catch (Exception e) {
            return null;
        }
    }
}
