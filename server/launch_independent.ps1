$shell = New-Object -ComObject Shell.Application
$pythonw = "C:\Users\chyad\AppData\Local\Programs\Python\Python314\pythonw.exe"
$script = "C:\Users\chyad\.gemini\antigravity\scratch\pc-remote\server\server.py"
$dir = "C:\Users\chyad\.gemini\antigravity\scratch\pc-remote\server"

$shell.ShellExecute($pythonw, "`"$script`"", $dir, "open", 0)
Write-Host "Server launched via Explorer Shell!"
