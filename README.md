# BGRCAPP_BE

> 북구장애인종합복지관 - 무료급식사업 출석 관리 시스템 

## 🛠️ Tech Stack

- Java 17
- Spring Boot 3.4.2
- Apache POI 5.2.5 (Excel 처리)

## 📋 Features

- **QR 스캔 출석 체크**: QR 코드를 통한 빠르고 정확한 출석 확인
- **명단 관리**: Excel 파일 업로드를 통한 대상자 명단 관리
- **출석 로그 자동 생성**: 일별 출석 기록 자동 저장 및 관리
- **관리자 페이지**: 명단 업로드 및 시스템 상태 확인
- **중복 체크 방지**: 동일 인원 중복 출석 자동 방지


## ☁️ How to run

1. **Clone project**

   ```bash
   git clone https://github.com/feralshining/BGRCAPP_BE.git
   cd attendance
   ```

2. **Set environment variable**<br>
   Add `src/main/resources/application.yml`

   ```yaml
   spring:
     application:
       name: attendance
     servlet:
       multipart:
         max-file-size: 10MB
         max-request-size: 10MB
         enabled: true

   # 파일 업로드 저장 경로
   file:
     upload:
       dir: ./data/userlist

   # 출석 로그 파일 저장 경로
   attendance:
     log:
       dir: ./data/attendance
   ```

3. **Run application**

   ```bash
   # Gradle을 통한 실행
   ./gradlew bootRun

   # 또는 IDE에서 AttendanceApplication.groovy 실행
   ```

4. **Access Admin Page**
   
   브라우저에서 `http://localhost:8080/admin.html` 접속

## 📁 API Documents

<details>
<summary>펼쳐 보기</summary>

| Feature | Method | URI                 | Description |
|---------|--------|---------------------|-------------|
| 🏥 **서버 상태** | |                     | |
| 서버 상태 확인 | `GET` | `/api/status`       | 서버 정상 동작 확인 |
| 🔍 **QR 스캔** | |                     | |
| QR 스캔 처리 | `POST` | `/api/qr/scan`      | QR 코드 스캔 및 출석 체크 |
| ⚙️ **관리자** | |                     | |
| 시스템 설정 조회 | `GET` | `/api/admin/config` | 현재 시스템 설정 정보 조회 |
| 명단 파일 업로드 | `POST` | `/api/admin/upload` | Excel 명단 파일 업로드 |

### Request/Response Examples

#### QR 스캔 처리
```json
// POST /api/qr/scan
{
  "qrData": "홍길동/1990-01-01/BGRC"
}

// Response
{
  "code": 1000,
  "success": true,
  "message": "출석 체크가 완료되었습니다.",
  "data": {
    "name": "홍길동",
    "birthDate": "1990-01-01",
    "scannedAt": "2026-02-03T15:30:00"
  }
}
```

#### 시스템 설정 조회
```json
// GET /api/admin/config

// Response
{
  "code": 1000,
  "success": true,
  "message": "요청에 성공했습니다.",
  "data": {
    "userCount": 150,
    "excelPath": "./data/userlist",
    "fileExists": true
  }
}
```

</details>

## 🗂️ Data Format

### Excel 명단 파일 형식

| 성명 | 생년월일 | 비고 |
|------|----------|------|
| 홍길동 | 1990-01-01 | |
| 김철수 | 1985.05.15 | |
| 이영희 | 19920320 | |

**지원 형식:**
- 생년월일: `YYYY-MM-DD`, `YYYY.MM.DD`, `YYYY/MM/DD`, `YYYYMMDD`
- Excel 날짜 포맷 자동 변환

### 출석 로그 파일 형식

```
[출석 인원 : 3]
홍길동/1990-01-01/12:30:45
김철수/1985-05-15/12:31:20
이영희/1992-03-20/12:32:10
```

## 🚀 Future Enhancements

- [ ] 데이터베이스 연동 
- [ ] 통계 대시보드 추가
- [ ] 출석 데이터 엑셀 내보내기

## 👨‍💻 Contributors

| Developer |
| --------- |
| <img src="https://github.com/feralshining.png" width="100" /> |
| [@feralshining](https://github.com/feralshining) |


---