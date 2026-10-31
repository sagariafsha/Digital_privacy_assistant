@echo off
title Digital Privacy Assistant — Backend Server
echo ========================================================
echo         STARTING DIGITAL PRIVACY ASSISTANT BACKEND
echo ========================================================
echo.
echo Checking Java / Maven environment...
cd /d "%~dp0backend"

where mvn >nul 2>nul
if %errorlevel% equ 0 (
    echo Starting server with Maven on port 8080...
    echo Backend will be available at http://localhost:8080
    mvn compile exec:java
    goto end
)

where java >nul 2>nul
if %errorlevel% equ 0 (
    echo Starting compiled backend with Java...
    java -cp "target/classes;target/dependency/*;." Main
    goto end
)

echo [!] Maven or Java not found in PATH.
echo Please install Java 17+ or Maven to run the automated Gmail OAuth scanner.
pause

:end
