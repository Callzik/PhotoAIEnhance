@echo off
cd /d "%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0setup-and-start.ps1"
echo.
echo Please check messages above for any errors.
pause
