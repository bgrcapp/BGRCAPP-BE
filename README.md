# BGRCAPP-BE

북구장애인종합복지관 무료급식사업의 QR 출석과 월별 출석 일지를 관리하는 Windows 운영용 시스템입니다. 출석 원본은 데이터베이스가 아닌 Excel 파일로 보관하며, 서버 프로그램은 명단과 출석 일지를 읽고 기록하는 역할을 합니다.

## 주요 기능

- QR의 `이름 + 생년월일`로 대상자를 확인하고 출석 처리
- 관리자 페이지에서 대상자 명단 Excel 업로드, 날짜별 출석 조회, 출석/결석 정정, 월별 일지 내려받기
- `무료급식 일일 식사내역_YY.M_일지.xlsx` 형식의 월별 출석 일지 자동 생성
- 월별 일지의 `o` 표시를 기준으로 누계 제공 건수, 월별 현황, 개인별 이용 횟수 통계 제공
- 개인 통계는 `이름 + 생년월일`을 식별 기준으로 사용하며, 과거 일지의 전화번호를 현재 명단과 연결해 연번 변경에도 같은 이용자로 집계
- 2026년 1~7월의 기존 일지를 JAR에 보관하고, 운영 폴더에 없는 달만 한 번 복원
- 서버 재시작이나 날짜 변경 시 기존 월별 일지를 다시 만들거나 덮어쓰지 않음
- 도움말, 대시보드, 통계 화면을 좌측 메뉴에서 이동
- Windows 런처를 통한 시작 시 1회 자동 업데이트 및 실패 시 이전 정상 버전 복구

## 시스템 흐름

```text
태블릿 QR 스캔
  → Windows 출석 서버 API
  → 활성 명단 확인 (이름 + 생년월일)
  → 해당 월 출석 일지에 o 기록
  → 관리자 페이지·통계에서 같은 일지를 조회

Windows 런처 시작
  → 업데이트 manifest 확인
  → 서명·SHA-256 검증
  → 새 JAR 기동 확인 후 적용 또는 기존 버전 실행
```

출석 데이터는 서버 JAR과 분리된 `data` 폴더에 저장됩니다. 따라서 업데이트로 JAR이 바뀌어도 명단과 월별 출석 일지는 유지됩니다.

## 기술 구성

- Java 17
- Spring Boot 4
- Apache POI
- 정적 관리자 화면: HTML, CSS, JavaScript
- Windows 자동 업데이트: Ed25519 서명, SHA-256 검증
- 업데이트 파일 공개: Ubuntu의 Cloudflare Tunnel

## 개발 환경 실행

```bash
cd BGRCAPP-BE
bash gradlew bootRun
```

브라우저에서 [http://localhost:8080](http://localhost:8080)으로 접속합니다.

- 대시보드: `/`
- 통계: `/statistics`
- 도움말: `/guide`
- 서버 버전 확인: `/api/version`

전체 빌드와 테스트는 다음 명령으로 실행합니다.

```bash
bash gradlew clean build -PreleaseVersion=1.2.7
```

## Windows 운영 배포

### 최초 설치

빌드 또는 배포 스크립트 실행 후 Windows 설치 ZIP이 생성됩니다.

```text
build/distributions/bgrc-attendance-<버전>-windows.zip
```

1. Windows PC에 Java 17 이상을 설치합니다.
2. ZIP을 예를 들어 `C:\무료급식출석`에 압축 해제합니다.
3. `launcher\run-attendance-launcher.bat`을 실행합니다.
4. 관리자 페이지에서 원본 명단 Excel을 업로드합니다.

기존 PC를 교체하는 경우에는 기존 설치 폴더의 `data` 폴더를 새 설치 폴더로 함께 복사합니다. 기존 명단, 월별 출석 일지, 로그가 그대로 이어집니다.

### 자동 업데이트

최초 설치 이후에는 별도의 JAR 전달이 필요 없습니다. 현장 PC는 반드시 `launcher\run-attendance-launcher.bat`으로 실행합니다.

1. 런처가 서버 시작 전에 최신 `manifest.json`을 확인합니다.
2. Ed25519 공개키로 manifest 서명을 검증합니다.
3. 새 버전이면 JAR을 내려받아 SHA-256을 다시 계산합니다.
4. 해시가 일치하는 JAR만 임시로 실행하고 `/api/version`으로 기동을 확인합니다.
5. 정상 기동하면 활성 버전을 바꾸고, 실패하거나 인터넷이 끊기면 기존 정상 버전을 실행합니다.

업데이트는 서버를 켤 때 한 번만 확인합니다. 서버가 이미 실행 중인 동안에는 버전이 바뀌지 않습니다.

## 데이터와 Excel 양식

```text
data/
├── userlist/
│   └── attendance-roster.xlsx                 # 현재 활성 명단
├── attendance/
│   ├── 무료급식 일일 식사내역_26.8_일지.xlsx  # 월별 출석 일지
│   └── holidays/                              # 공휴일 조회 캐시
└── logs/
    └── attendance.log                         # 서버 로그
```

### 명단

명단 Excel에는 최소한 아래 열이 필요합니다.

| 연번 | 성명 | 전화번호 | 생년월일 |
| --- | --- | --- | --- |
| 1 | 홍길동 | 010-1234-5678 | 1959-03-27 |

- QR 출석의 정식 식별값은 `성명 + 생년월일`입니다.
- 전화번호는 기존 월별 일지와 현재 명단을 연결해 통계에서 연번 변경을 안전하게 이어가기 위해 사용합니다.
- 생년월일은 `YYYY-MM-DD`, `YYYY.MM.DD`, `YYYY/MM/DD`, `YYYYMMDD`, Excel 날짜 셀을 지원합니다.

### 월별 출석 일지와 통계

- 출석 일지는 `data/attendance` 바로 아래에 저장됩니다.
- 새 달에 처음 출석을 처리하거나 해당 달을 조회하면 현재 명단 양식으로 새 파일이 생성됩니다.
- 통계는 페이지를 열거나 새로고침할 때 `data/attendance`의 월별 `.xlsx` 파일을 다시 읽습니다.
- `YY.M`과 `YY.MM` 파일명 모두 인식합니다. 예: `27.9`, `27.09`
- 일지의 `내역1`, `내역2` 시트와 `연번`, `성명`, `전화번호`, 날짜 열, 출석 표시 `o`를 기준으로 집계합니다.
- 관리자가 출석/결석을 바꾸면 해당 월 Excel의 표시와 메모리 상태가 함께 변경됩니다.

## API

| 기능 | 메서드 | 경로 |
| --- | --- | --- |
| QR 출석 처리 | `POST` | `/api/qr/scan` |
| 현재 명단 상태 | `GET` | `/api/admin/config` |
| 날짜별 출석 현황 | `GET` | `/api/admin/attendance?date=YYYY-MM-DD` |
| 출석/결석 전환 | `POST` | `/api/admin/attendance/toggle` |
| 월별 일지 내보내기 | `GET` | `/api/admin/attendance/export?date=YYYY-MM-DD` |
| 명단 업로드 | `POST` | `/api/admin/roster` |
| 출석 통계 | `GET` | `/api/admin/statistics` |
| 서버 버전 | `GET` | `/api/version` |

## 새 버전 배포

개발 Mac에서 버전 번호를 올려 다음 명령을 실행합니다.

```bash
cd BGRCAPP-BE
bash deploy/update-server/publish-bgrc-release.sh 1.2.8
```

스크립트가 다음을 순서대로 수행합니다.

1. 전체 빌드와 테스트
2. Windows 최초 설치 ZIP 생성
3. 로컬 개인키로 manifest 서명
4. SSH로 Ubuntu 업데이트 서버에 새 JAR 업로드
5. 마지막에 manifest와 서명을 교체해 새 버전 공개

업데이트 서버는 `https://bgrc.howmanycals.online`으로 공개됩니다. Cloudflare Tunnel이 Ubuntu의 로컬 업데이트 서버와 Cloudflare를 연결하므로 Ubuntu에서 공인 IP, 80/443 포트 개방, 공유기 포트포워딩이 필요하지 않습니다.

### 업데이트 검증 구조

```text
manifest 서명 (Ed25519)
  → manifest가 우리 배포본인지 검증

JAR SHA-256
  → 다운로드한 JAR이 manifest가 지시한 정확한 파일인지 검증
```

개인키는 개발 Mac에만 보관합니다. Ubuntu, Cloudflare, Windows 배포본에는 개인키를 넣지 않습니다.

## 운영 시 유의사항

- 실행 중인 월별 일지나 활성 명단 Excel을 Excel 프로그램에서 열어 두면 Windows 파일 잠금으로 저장·업로드가 실패할 수 있습니다.
- 출석 테스트 기록은 관리자 페이지에서 해당 날짜의 출석 버튼을 눌러 결석으로 되돌립니다.
- 자동 업데이트를 사용하려면 직접 `java -jar`로 실행하지 말고 런처 BAT로 실행해야 합니다.
- `data` 폴더는 운영 원본이므로 정기적으로 별도 백업합니다.
