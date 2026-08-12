@echo off
setlocal

rem launcher 폴더의 상위(설치 루트)를 작업 폴더로 고정한다.
rem 이렇게 해야 data\ 아래의 명단·출석 일지가 JAR 업데이트와 분리된다.
cd /d "%~dp0.."

java -jar "launcher\attendance-launcher.jar"
set EXIT_CODE=%ERRORLEVEL%

if not "%EXIT_CODE%"=="0" (
    echo.
    echo 출석 서버 실행이 종료되었습니다. 위의 오류 내용을 확인하세요.
    pause
)

exit /b %EXIT_CODE%
