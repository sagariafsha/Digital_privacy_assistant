@echo off
title Digital Privacy Assistant
echo ========================================================
echo         DIGITAL PRIVACY ASSISTANT
echo ========================================================
echo.
echo [1/2] Opening Web Application in your default browser...
start "" "%~dp0frontend\index.html"
echo.
echo [2/2] Success! The Digital Privacy Assistant is running.
echo.
echo Features available immediately:
echo  * Email Footprint & Breach Exposure Audit
echo  * Discovered Accounts & Privacy Risk Center
echo  * Real Password Security & Entropy Lab
echo  * Suspicious URL & Phishing Link Detector
echo  * Live Browser Leak & Fingerprint Inspector
echo  * GDPR Article 17 / CCPA Data Deletion Generator
echo  * Unused Account Reminder & Hardening Checklist
echo.
echo Note: If you also wish to launch the Java Backend server:
echo  1. Open a new terminal in the backend folder:
echo     cd backend
echo  2. Run Maven:
echo     mvn compile exec:java
echo.
pause
