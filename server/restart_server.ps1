$procs = Get-CimInstance Win32_Process -Filter "CommandLine LIKE '%server.py%'"
if ($procs) {
    $procs | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
}
Start-Sleep -Milliseconds 500

$cmd = '"C:\Users\chyad\AppData\Local\Programs\Python\Python314\pythonw.exe" "C:\Users\chyad\.gemini\antigravity\scratch\pc-remote\server\server.py"'
Invoke-WmiMethod -Class Win32_Process -Name Create -ArgumentList $cmd
Write-Host "PC Remote Server restarted in background!"
