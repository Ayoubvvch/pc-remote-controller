@echo off
:: Batch script to register PC Remote Server into Windows Task Scheduler with Elevation
net session >nul 2>&1
if %errorlevel% neq 0 (
    echo Requesting Administrator privileges...
    powershell -Command "Start-Process cmd -ArgumentList '/c \"\"%~dp0register_task_scheduler_admin.bat\"\"' -Verb RunAs"
    exit /b
)

echo ========================================================
echo Registering PC Remote Server in Task Scheduler...
echo ========================================================

schtasks /create /tn "PCRemoteServer" /tr "\"C:\Users\chyad\AppData\Local\Programs\Python\Python314\pythonw.exe\" \"C:\Users\chyad\.gemini\antigravity\scratch\pc-remote\server\server.py\"" /sc onlogon /rl highest /f

if %errorlevel% equ 0 (
    echo.
    echo [SUCCESS] Scheduled Task 'PCRemoteServer' registered successfully!
    echo It will start automatically whenever you log in with highest privileges.
) else (
    echo.
    echo [ERROR] Failed to register task.
)
timeout /t 5
