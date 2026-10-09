# Install PC Remote Server Autostart in Task Scheduler and Startup
$pythonw = "C:\Users\chyad\AppData\Local\Programs\Python\Python314\pythonw.exe"
$scriptPath = "C:\Users\chyad\.gemini\antigravity\scratch\pc-remote\server\server.py"
$workDir = "C:\Users\chyad\.gemini\antigravity\scratch\pc-remote\server"

Write-Host "Creating Scheduled Task for PC Remote..." -ForegroundColor Cyan

# 1. Register Scheduled Task
try {
    $action = New-ScheduledTaskAction -Execute $pythonw -Argument "`"$scriptPath`"" -WorkingDirectory $workDir
    $trigger = New-ScheduledTaskTrigger -AtLogOn
    $settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -ExecutionTimeLimit (New-TimeSpan -Days 365) -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1)
    Register-ScheduledTask -TaskName "PCRemoteServer" -Action $action -Trigger $trigger -Settings $settings -Force
    Write-Host "✅ Scheduled Task 'PCRemoteServer' registered successfully!" -ForegroundColor Green
} catch {
    Write-Host "⚠️ Task Scheduler registration error: $_" -ForegroundColor Yellow
}

# 2. Also create a shortcut in Windows Startup folder (as an extra layer of 100% guarantee)
$startupFolder = [System.Environment]::GetFolderPath([System.Environment+SpecialFolder]::Startup)
$shortcutPath = Join-Path $startupFolder "PCRemoteServer.lnk"

try {
    $wsh = New-Object -ComObject WScript.Shell
    $shortcut = $wsh.CreateShortcut($shortcutPath)
    $shortcut.TargetPath = $pythonw
    $shortcut.Arguments = "`"$scriptPath`""
    $shortcut.WorkingDirectory = $workDir
    $shortcut.WindowStyle = 7 # Minimized
    $shortcut.Description = "PC Remote Control Server"
    $shortcut.Save()
    Write-Host "✅ Startup folder shortcut created at: $shortcutPath" -ForegroundColor Green
} catch {
    Write-Host "⚠️ Startup shortcut error: $_" -ForegroundColor Yellow
}

# 3. Start the server right now using pythonw (headless background)
Write-Host "Starting PC Remote Server now..." -ForegroundColor Cyan
Start-Process -FilePath $pythonw -ArgumentList "`"$scriptPath`"" -WorkingDirectory $workDir

Write-Host "Done! Server is running in the background and will start automatically with Windows." -ForegroundColor Green
