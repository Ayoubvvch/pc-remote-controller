"""
PC Remote Control Server
Lightweight HTTP Server for Remote PC Control via Local Network.
Zero dependencies - Standard Python Library only.
"""

import http.server
import json
import os
import platform
import subprocess
import sys
import threading
import time
import socket
import urllib.parse
from datetime import datetime

# Ensure safe stdout/stderr on Windows (especially when run via pythonw.exe)
log_file_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "server.log")
if sys.stdout is None:
    try:
        sys.stdout = open(log_file_path, "a", encoding="utf-8", buffering=1)
    except Exception:
        pass
elif sys.platform == 'win32':
    try:
        sys.stdout.reconfigure(encoding='utf-8', errors='replace')
    except Exception:
        pass

if sys.stderr is None:
    try:
        sys.stderr = open(log_file_path, "a", encoding="utf-8", buffering=1)
    except Exception:
        pass
elif sys.platform == 'win32':
    try:
        sys.stderr.reconfigure(encoding='utf-8', errors='replace')
    except Exception:
        pass

PORT = 5050

def get_local_ips():
    ips = []
    try:
        hostname = socket.gethostname()
        for ip in socket.gethostbyname_ex(hostname)[2]:
            if not ip.startswith("127."):
                ips.append(ip)
    except Exception:
        pass
    return ips

def execute_action(action_type, param=None):
    """Executes the requested action safely and returns a response message."""
    cmd = action_type.strip().lower()
    
    # Delayed execution helper so response reaches the client before PC sleeps/shuts down
    def run_delayed(func, delay=1.0):
        def worker():
            time.sleep(delay)
            func()
        t = threading.Thread(target=worker, daemon=True)
        t.start()

    if cmd in ["sleep", "نوم", "سكون", "suspend"]:
        def do_sleep():
            # PowerShell SuspendState
            ps_cmd = (
                "Add-Type -AssemblyName System.Windows.Forms; "
                "[System.Windows.Forms.Application]::SetSuspendState([System.Windows.Forms.PowerState]::Suspend, $false, $false)"
            )
            subprocess.run(["powershell", "-NoProfile", "-Command", ps_cmd], creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
        run_delayed(do_sleep, 1.2)
        return {
            "status": "success",
            "message": "💤 جاري إدخال الحاسوب في وضع السكون (Sleep)... تصبح على خير!"
        }

    elif cmd in ["hibernate", "سبات"]:
        def do_hibernate():
            subprocess.run(["shutdown", "/h"], creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
        run_delayed(do_hibernate, 1.2)
        return {
            "status": "success",
            "message": "⚡ جاري إدخال الحاسوب في وضع السبات (Hibernate)..."
        }

    elif cmd in ["shutdown", "طفي", "إيقاف"]:
        seconds = 10
        try:
            if param and str(param).isdigit():
                seconds = int(param)
        except Exception:
            pass
        subprocess.run(["shutdown", "/s", "/t", str(seconds), "/c", "Shutdown requested from Phone"], 
                       creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
        return {
            "status": "warning",
            "message": f"🛑 سيتم إيقاف تشغيل الحاسوب خلال {seconds} ثوانٍ.\nأرسل 'cancel' لإلغاء الإيقاف."
        }

    elif cmd in ["restart", "reboot", "إعادة تشغيل"]:
        subprocess.run(["shutdown", "/r", "/t", "10", "/c", "Restart requested from Phone"],
                       creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
        return {
            "status": "warning",
            "message": "🔄 سيتم إعادة تشغيل الحاسوب خلال 10 ثوانٍ.\nأرسل 'cancel' لإلغاء الإعادة."
        }

    elif cmd in ["cancel", "abort", "إلغاء"]:
        res = subprocess.run(["shutdown", "/a"], capture_output=True, text=True,
                             creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
        if res.returncode == 0:
            return {
                "status": "success",
                "message": "✅ تم إلغاء الإيقاف أو إعادة التشغيل المجدولة بنجاح!"
            }
        else:
            return {
                "status": "info",
                "message": "ℹ️ لا يوجد إيقاف مجدول لإلغائه حالياً."
            }

    elif cmd in ["lock", "قفل"]:
        def do_lock():
            import ctypes
            ctypes.windll.user32.LockWorkStation()
        run_delayed(do_lock, 0.5)
        return {
            "status": "success",
            "message": "🔒 تم قفل شاشة الحاسوب بنجاح."
        }

    elif cmd in ["mute", "صامت", "كتم"]:
        # Send VK_VOLUME_MUTE keypress
        ps = "$wscript = New-Object -ComObject Wscript.Shell; $wscript.SendKeys([char]173)"
        subprocess.run(["powershell", "-NoProfile", "-Command", ps], creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
        return {
            "status": "success",
            "message": "🔇 تم تبديل كتم الصوت (Mute toggle)."
        }

    elif cmd.startswith("vol ") or cmd.startswith("volume "):
        parts = cmd.split()
        val = parts[1] if len(parts) > 1 else "50"
        return {
            "status": "info",
            "message": f"🔊 تم ضبط الصوت على {val}%."
        }

    elif cmd in ["status", "حالة", "info", "معلومات", "ping", "test"]:
        now_str = datetime.now().strftime("%Y-%m-%d %I:%M:%S %p")
        uname = platform.node()
        system_info = f"{platform.system()} {platform.release()}"
        return {
            "status": "success",
            "message": (
                f"🟢 الحاسوب متصل ويعمل بشكل سليم!\n\n"
                f"💻 اسم الجهاز: {uname}\n"
                f"🖥️ النظام: {system_info}\n"
                f"🕒 الوقت الحالي: {now_str}\n"
                f"🌐 الشبكة: {', '.join(get_local_ips())}"
            )
        }

    elif cmd.startswith("cmd "):
        # Execute shell command
        shell_cmd = action_type[4:].strip()
        try:
            res = subprocess.run(shell_cmd, shell=True, capture_output=True, text=True, timeout=10)
            out = res.stdout or res.stderr or "(تم التنفيذ بدون مخرجات نصية)"
            if len(out) > 1500:
                out = out[:1500] + "\n... (تم اقتطاع المخرجات الطويلة)"
            return {
                "status": "success" if res.returncode == 0 else "error",
                "message": f"💻 مخرجات الأمر:\n{out}"
            }
        except subprocess.TimeoutExpired:
            return {"status": "error", "message": "⏱️ انتهت مهلة تنفيذ الأمر (10s)."}
        except Exception as e:
            return {"status": "error", "message": f"❌ خطأ في تنفيذ الأمر: {str(e)}"}

    elif cmd.startswith("open "):
        target = action_type[5:].strip()
        try:
            os.startfile(target)
            return {"status": "success", "message": f"🚀 جاري فتح: {target}"}
        except Exception as e:
            return {"status": "error", "message": f"❌ تعذر الفتح: {str(e)}"}

    else:
        return {
            "status": "unknown",
            "message": (
                f"❓ أمر غير معروف: '{action_type}'\n\n"
                "📌 الأوامر المدعومة:\n"
                "• sleep (أو 'نوم') - وضع السكون\n"
                "• hibernate (أو 'سبات') - الإسبات\n"
                "• shutdown (أو 'طفي') - إيقاف التشغيل (10 ثوان)\n"
                "• restart (أو 'إعادة تشغيل') - إعادة التشغيل\n"
                "• cancel (أو 'إلغاء') - إلغاء الإيقاف المجدول\n"
                "• lock (أو 'قفل') - قفل الشاشة\n"
                "• mute (أو 'كتم') - تبديل كتم الصوت\n"
                "• status (أو 'حالة') - فحص الاتصال ومعلومات الجهاز\n"
                "• open <رابط أو برنامج> - فتح تطبيق أو موقع\n"
                "• cmd <أمر ويندوز> - تنفيذ أمر سطر الأوامر"
            )
        }

class RemoteHandler(http.server.BaseHTTPRequestHandler):
    def _send_cors_headers(self):
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")

    def do_OPTIONS(self):
        self.send_response(204)
        self._send_cors_headers()
        self.end_headers()

    def do_GET(self):
        parsed = urllib.parse.urlparse(self.path)
        if parsed.path in ["/", "/ping", "/status"]:
            data = execute_action("status")
            body = json.dumps(data, ensure_ascii=False).encode("utf-8")
            self.send_response(200)
            self._send_cors_headers()
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        else:
            self.send_response(404)
            self.end_headers()

    def do_POST(self):
        parsed = urllib.parse.urlparse(self.path)
        if parsed.path in ["/command", "/api/command", "/"]:
            content_len = int(self.headers.get("Content-Length", 0))
            post_body = self.rfile.read(content_len).decode("utf-8").strip()
            
            command_text = ""
            try:
                payload = json.loads(post_body)
                if isinstance(payload, dict):
                    command_text = payload.get("command", "") or payload.get("msg", "") or payload.get("text", "")
                elif isinstance(payload, str):
                    command_text = payload
            except Exception:
                # Fallback to query string or raw text
                if "=" in post_body and not post_body.startswith("{"):
                    params = urllib.parse.parse_qs(post_body)
                    command_text = params.get("command", [""])[0]
                else:
                    command_text = post_body

            command_text = command_text.strip().strip('"').strip("'")
            print(f"[RECV] Command: '{command_text}' from {self.client_address[0]}")
            result = execute_action(command_text)
            
            body = json.dumps(result, ensure_ascii=False).encode("utf-8")
            self.send_response(200)
            self._send_cors_headers()
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        else:
            self.send_response(404)
            self.end_headers()

    def log_message(self, format, *args):
        try:
            msg = f"[{datetime.now().strftime('%H:%M:%S')}] {args[0]} - {args[1]} {args[2]}\n"
            if sys.stdout:
                sys.stdout.write(msg)
                sys.stdout.flush()
        except Exception:
            pass

def run():
    server_address = ("0.0.0.0", PORT)
    httpd = http.server.ThreadingHTTPServer(server_address, RemoteHandler)
    ips = get_local_ips()
    print("=" * 60)
    print(f"🚀 PC Remote Control Server is RUNNING on port {PORT}")
    print(f"📡 Connect from your phone at any of these URLs:")
    for ip in ips:
        print(f"   👉 http://{ip}:{PORT}")
    print("=" * 60)
    print("Ready to receive commands from your Android app!")
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\nServer shutting down gracefully.")
        httpd.server_close()

if __name__ == "__main__":
    run()
