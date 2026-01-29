package com.bgrc.attendance.service;

import com.bgrc.attendance.dto.QrScanRequest;
import com.bgrc.attendance.dto.QrScanResponse;
import com.bgrc.attendance.model.User;
import org.springframework.stereotype.Service;

@Service
public class QrService {
    private static record ParsedQrData(String name, String birthDate, String issuer){}; // record 사용을 위한 선언
    private static final String VALID_ISSUER = "북구장애인종합복지관";

    private QrScanResponse processScan(QrScanRequest request){
        try {
            // 0. 반환할 response 생성
            QrScanResponse response = new QrScanResponse();
            // 1. QR 데이터 파싱
            ParsedQrData data = parseQr(request.getQrData()); // JSON으로 넘어온 값 분리하기
            // 2. 데이터 검증 - 발급처 검증
            if (!VALID_ISSUER.equals(data.issuer)) {
                response.setSuccess(false);
                response.setMessage("유효한 QR 코드가 아닙니다.");
                return response;
            };
            // 3. 명단 확인
            User user = new ExcelService().findUser(data.name, data.birthDate);
            if (user == null){
                response.setSuccess(false);
                response.setMessage("명단에 등록되지 않은 이용자입니다.");
                return response;
            }
            // 4. 중복 출석 확인

            // 5. 출석 로그 파일 생성

            // 6. 성공 응답
            return null;
        } catch (IllegalArgumentException e) {
            return new QrScanResponse(false, e.getMessage(), null, null);
        }
    }

    /**
     * Request로 날아온 JSON 형태의 QR 데이터 값을 끊어서 반환합니다.
     * @param qrData Request로 날아온 JSON 형태의 값
     */
    private ParsedQrData parseQr(String qrData){
        String[] data = qrData.split("/");
        return new ParsedQrData(
                data[0].strip(), // name
                data[1].strip(), // birthDate
                data[2].strip()  // issuer
        );
    }
}
