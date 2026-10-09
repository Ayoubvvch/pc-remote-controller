Set WshShell = CreateObject("WScript.Shell")
WshShell.Run "python """ & Replace(WScript.ScriptFullName, "start_silent.vbs", "server.py") & """", 0, False
