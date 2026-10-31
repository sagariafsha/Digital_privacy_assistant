@echo off
title Digital Privacy Assistant Local Server
echo ========================================================
echo       STARTING DIGITAL PRIVACY ASSISTANT SERVER
echo ========================================================
echo.
echo Starting local web server on port 5000...
echo.

:: Try Python if available
where python >nul 2>nul
if %errorlevel% equ 0 (
    echo Python detected. Launching http://localhost:5000...
    start http://localhost:5000
    python -m http.server 5000 --directory "%~dp0frontend"
    goto end
)

:: Try npx serve or node if available
where npx >nul 2>nul
if %errorlevel% equ 0 (
    echo Node/npx detected. Launching http://localhost:5000...
    start http://localhost:5000
    npx serve "%~dp0frontend" -l 5000
    goto end
)

:: Fallback direct launch
echo Launching direct app window...
start msedge.exe --app="%~dp0frontend\index.html"
if %errorlevel% neq 0 (
    start "" "%~dp0frontend\index.html"
)

:end
