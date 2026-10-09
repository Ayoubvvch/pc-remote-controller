@echo off
title Stop PC Remote Server
powershell -NoProfile -Command "$procs = Get-CimInstance Win32_Process -Filter \"CommandLine LIKE '%%server.py%%'\"; if ($procs) { $procs | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }; Write-Host '[OK] PC Remote Server stopped.' -ForegroundColor Green } else { Write-Host 'No running server found.' -ForegroundColor Yellow }"
pause
