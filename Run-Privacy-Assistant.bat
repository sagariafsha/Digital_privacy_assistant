@echo off
title Digital Privacy Assistant Website
setlocal
set "GOOGLE_CLIENT_ID=990519841196-d046a41pajcn0q0f83cdraisbcfqqgjo.apps.googleusercontent.com"
set "PORT=5500"
echo Starting the website at http://127.0.0.1:5500...
start "Digital Privacy Assistant backend" /min cmd /c ""%~dp0Start-Backend.bat""
timeout /t 4 /nobreak >nul
start "" "http://127.0.0.1:5500"
endlocal
