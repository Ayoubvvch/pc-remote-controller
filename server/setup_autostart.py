import os
import sys
import winreg
import subprocess

if sys.platform == 'win32':
    try:
        sys.stdout.reconfigure(encoding='utf-8', errors='replace')
    except Exception:
        pass

def setup():
    pythonw_path = os.path.join(os.path.dirname(sys.executable), "pythonw.exe")
    server_script = os.path.abspath(os.path.join(os.path.dirname(__file__), "server.py"))
    server_dir = os.path.dirname(server_script)
    
    command = f'"{pythonw_path}" "{server_script}"'
    
    print(f"Configuring PC Remote Server Autostart:")
    print(f"Command: {command}")
    
    # 1. Add to Windows Registry Run Key (Runs on user login)
    try:
        key = winreg.OpenKey(
            winreg.HKEY_CURRENT_USER,
            r"Software\Microsoft\Windows\CurrentVersion\Run",
            0,
            winreg.KEY_SET_VALUE
        )
        winreg.SetValueEx(key, "PCRemoteServer", 0, winreg.REG_SZ, command)
        winreg.CloseKey(key)
        print("✅ Added to Windows Registry: HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run")
    except Exception as e:
        print(f"⚠️ Registry setup failed: {e}")

    # 2. Add shortcut to Windows Startup folder
    try:
        startup_dir = os.path.join(os.environ["APPDATA"], r"Microsoft\Windows\Start Menu\Programs\Startup")
        vbs_launcher = os.path.join(server_dir, "start_silent.vbs")
        
        # Write a clean startup .bat or .vbs in Startup folder
        target_vbs = os.path.join(startup_dir, "PCRemote_Autostart.vbs")
        with open(target_vbs, "w", encoding="utf-8") as f:
            f.write(f'Set WshShell = CreateObject("WScript.Shell")\n')
            f.write(f'WshShell.CurrentDirectory = "{server_dir}"\n')
            f.write(f'WshShell.Run """{pythonw_path}"" ""{server_script}""", 0, False\n')
        print(f"✅ Added startup script to: {target_vbs}")
    except Exception as e:
        print(f"⚠️ Startup folder setup failed: {e}")

    # 3. Check if server is currently running, if not start it
    import socket
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    is_running = False
    try:
        s.connect(("127.0.0.1", 5050))
        is_running = True
        s.close()
    except Exception:
        pass

    if is_running:
        print("✅ PC Remote Server is ALREADY RUNNING on port 5050!")
    else:
        print("🚀 Starting PC Remote Server now in background...")
        subprocess.Popen([pythonw_path, server_script], cwd=server_dir, creationflags=0x08000000) # CREATE_NO_WINDOW
        print("✅ PC Remote Server started successfully!")

if __name__ == "__main__":
    setup()
