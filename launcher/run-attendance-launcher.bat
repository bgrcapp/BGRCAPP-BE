@echo off
setlocal EnableExtensions

cd /d "%~dp0.."
if errorlevel 1 (
    echo Failed to open the installation directory.
    pause
    exit /b 1
)

where java >nul 2>&1
if errorlevel 1 (
    echo Java 17 or later was not found. Install Java and run this file again.
    pause
    exit /b 1
)

java -jar "launcher\attendance-launcher.jar"
set "EXIT_CODE=%ERRORLEVEL%"

if not "%EXIT_CODE%"=="0" (
    echo.
    echo Attendance server stopped. Review the messages above.
    pause
)

exit /b %EXIT_CODE%
