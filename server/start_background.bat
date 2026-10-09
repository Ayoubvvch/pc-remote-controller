@echo off
title Start PC Remote Server (Background)
powershell -NoProfile -Command "Invoke-WmiMethod -Class Win32_Process -Name Create -ArgumentList '\"C:\Users\chyad\AppData\Local\Programs\Python\Python314\pythonw.exe\" \"C:\Users\chyad\.gemini\antigravity\scratch\pc-remote\server\server.py\"'" >nul
echo ========================================================
echo [OK] PC Remote Server is running in background (Port 5050).
echo You can close this window now.
echo ========================================================
pause
