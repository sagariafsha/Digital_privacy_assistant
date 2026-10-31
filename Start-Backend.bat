@echo off
title Digital Privacy Assistant Backend Server
echo ========================================================
echo         STARTING DIGITAL PRIVACY ASSISTANT BACKEND
echo ========================================================
echo.
echo Checking Java / Maven environment...
set "GOOGLE_CLIENT_ID=990519841196-d046a41pajcn0q0f83cdraisbcfqqgjo.apps.googleusercontent.com"
set "PORT=5500"
cd /d "%~dp0backend"

where mvn >nul 2>nul
if %errorlevel% equ 0 (
    echo Starting server with Maven on port 5500...
    echo Backend will be available at http://127.0.0.1:5500
    mvn -s "%~dp0.maven-settings.xml" compile exec:java
    goto end
)

where java >nul 2>nul
if %errorlevel% equ 0 (
    echo Maven was not found. Please install Maven to run the website.
    goto end
)

echo [!] Java or Maven not found in PATH.
echo Please install Java 17 and Maven, then reopen this launcher.
pause

:end
