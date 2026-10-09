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

# Media & Custom Automation Config
MPV_PATH = r"C:\Apps\mpv\mpv.exe"
RAIN_VIDEO = r"F:\ZPCController\Rain.mp4"
MESSI_VIDEO = r"F:\ZPCController\Messi.mp4"

active_sleep_timer = None
active_sleep_details = None

def kill_mpv():
    """Closes any running mpv instances."""
    try:
        subprocess.run(["taskkill", "/f", "/im", "mpv.exe"], capture_output=True,
                       creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
        subprocess.run(["taskkill", "/f", "/im", "mpv.com"], capture_output=True,
                       creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
    except Exception:
        pass

def parse_duration_seconds(duration_str, default_sec=15 * 60):
    """Parses durations like '15min', '15m', '1h', '30s', '45' into seconds."""
    import re
    s = duration_str.strip().lower()
    if not s:
        return default_sec
    m = re.match(r"^([\d\.]+)\s*(h|hr|hours?|m|min|minutes?|s|sec|seconds?|دقيقة|ساعة|ثانية)?$", s)
    if m:
        val = float(m.group(1))
        unit = (m.group(2) or "m").lower()
        if unit in ["h", "hr", "hour", "hours", "ساعة"]:
            return int(val * 3600)
        elif unit in ["s", "sec", "second", "seconds", "ثانية"]:
            return int(val)
        else:
            return int(val * 60)
    return default_sec

def format_duration(seconds):
    """Formats seconds into readable string."""
    if seconds >= 3600:
        hrs = seconds / 3600
        return f"{hrs:.1f} ساعة" if hrs % 1 != 0 else f"{int(hrs)} ساعة"
    elif seconds >= 60:
        mins = seconds // 60
        return f"{mins} دقيقة"
    else:
        return f"{seconds} ثانية"

# Volume Control via vol.exe (Windows CoreAudio API)
VOL_EXE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "vol.exe")

def set_system_volume(level):
    """Sets Windows master volume to a percentage (0-100)."""
    try:
        val = int(level)
        val = max(0, min(100, val))
        if os.path.exists(VOL_EXE):
            res = subprocess.run([VOL_EXE, str(val)], capture_output=True, text=True,
                                 creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
            return val
    except Exception:
        pass
    return None

def get_system_volume():
    """Gets Windows master volume percentage."""
    try:
        if os.path.exists(VOL_EXE):
            res = subprocess.run([VOL_EXE], capture_output=True, text=True,
                                 creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
            out = res.stdout.strip()
            if out.startswith("GET:"):
                return out.replace("GET:", "").strip()
    except Exception:
        pass
    return None

def toggle_system_mute():
    """Toggles system mute."""
    try:
        if os.path.exists(VOL_EXE):
            res = subprocess.run([VOL_EXE, "mute"], capture_output=True, text=True,
                                 creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
            out = res.stdout.strip()
            return "MUTED:True" in out
    except Exception:
        pass
    return None

def execute_action(action_type, param=None):
    """Executes the requested action safely and returns a response message."""
    global active_sleep_timer, active_sleep_details
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

    elif cmd.startswith("sleep rain") or cmd == "sleep rain" or cmd.startswith("rain "):
        # Cancel any previous sleep timer
        if active_sleep_timer:
            try:
                active_sleep_timer.cancel()
            except Exception:
                pass
            active_sleep_timer = None

        # Parse duration (default 15 minutes)
        dur_part = cmd.replace("sleep rain", "").replace("rain", "").strip()
        sec = parse_duration_seconds(dur_part, default_sec=15 * 60)
        dur_human = format_duration(sec)

        # Close any current mpv instance
        kill_mpv()

        # Set system volume to 28% (25-30% range)
        set_system_volume(28)

        if not os.path.exists(RAIN_VIDEO):
            return {
                "status": "error",
                "message": f"❌ لم يتم العثور على ملف الفيديو: {RAIN_VIDEO}"
            }

        # Launch mpv with Rain.mp4 in fullscreen and infinite loop
        mpv_exec = MPV_PATH if os.path.exists(MPV_PATH) else "mpv"
        subprocess.Popen([mpv_exec, "--fs", "--loop-file=inf", RAIN_VIDEO],
                         creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)

        # Setup sleep timer callback
        def on_rain_timer_done():
            global active_sleep_timer, active_sleep_details
            kill_mpv()
            ps_cmd = (
                "Add-Type -AssemblyName System.Windows.Forms; "
                "[System.Windows.Forms.Application]::SetSuspendState([System.Windows.Forms.PowerState]::Suspend, $false, $false)"
            )
            subprocess.run(["powershell", "-NoProfile", "-Command", ps_cmd],
                           creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
            active_sleep_timer = None
            active_sleep_details = None

        active_sleep_timer = threading.Timer(sec, on_rain_timer_done)
        active_sleep_timer.daemon = True
        active_sleep_timer.start()
        active_sleep_details = {"name": "Rain Sleep", "seconds": sec, "text": dur_human, "start": time.time()}

        return {
            "status": "success",
            "message": (
                f"🌧️ تم تشغيل Rain.mp4 في وضع ملء الشاشة بصوت 28% (Fullscreen + Loop)!\n\n"
                f"⏱️ المدة المحددة: {dur_human}\n"
                f"💤 سينام الحاسوب تلقائياً بعد انتهاء الوقت.\n"
                f"💡 أرسل 'cancel' أو 'stop' لإلغاء الموقت وإغلاق الفيديو في أي وقت."
            )
        }

    elif cmd in ["messi", "ميسي", "messi mp4", "messi.mp4"]:
        kill_mpv()

        # Set system volume to 16%
        set_system_volume(16)

        if not os.path.exists(MESSI_VIDEO):
            return {
                "status": "error",
                "message": f"❌ لم يتم العثور على ملف الفيديو: {MESSI_VIDEO}"
            }

        mpv_exec = MPV_PATH if os.path.exists(MPV_PATH) else "mpv"
        subprocess.Popen([mpv_exec, "--fs", "--loop-file=inf", MESSI_VIDEO],
                         creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
        return {
            "status": "success",
            "message": "🐐⚽ تم تشغيل Messi.mp4 في وضع ملء الشاشة والتكرار المستمر بصوت 16%! 🔥\n💡 أرسل 'stop' لإغلاق الفيديو."
        }

    elif cmd in ["cancel", "abort", "إلغاء", "stop", "إيقاف", "close"]:
        had_timer = False
        if active_sleep_timer:
            try:
                active_sleep_timer.cancel()
            except Exception:
                pass
            active_sleep_timer = None
            active_sleep_details = None
            had_timer = True

        # Stop mpv player
        kill_mpv()

        # Cancel Windows shutdown if scheduled
        res = subprocess.run(["shutdown", "/a"], capture_output=True, text=True,
                             creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)

        if had_timer or res.returncode == 0:
            return {
                "status": "success",
                "message": "✅ تم إيقاف المشغل (mpv) وإلغاء موقت السكون المجدول بنجاح!"
            }
        else:
            return {
                "status": "success",
                "message": "✅ تم إغلاق المشغل والتأكد من عدم وجود أي موقت مجدول."
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
        is_muted = toggle_system_mute()
        if is_muted is True:
            return {
                "status": "success",
                "message": "🔇 تم كتم صوت الحاسوب (Muted)."
            }
        else:
            return {
                "status": "success",
                "message": "🔊 تم إلغاء كتم صوت الحاسوب (Unmuted)."
            }

    elif (cmd.startswith("sound") or cmd.startswith("vol") or 
          cmd.startswith("volume") or cmd.startswith("صوت")):
        import re
        nums = re.findall(r"\d+", cmd)
        if nums:
            val = int(nums[0])
            val = max(0, min(100, val))
            set_system_volume(val)
            return {
                "status": "success",
                "message": f"🔊 تم ضبط مستوى صوت الحاسوب على {val}%."
            }
        else:
            cur = get_system_volume()
            cur_str = f"{cur}%" if cur is not None else "غير معروف"
            return {
                "status": "info",
                "message": f"🔊 مستوى صوت الحاسوب الحالي: {cur_str}\n💡 لتغيير الصوت أرسل مثلاً: sound 40 أو sound 25"
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
                "• sleep rain <مدة> - تشغيل Rain.mp4 بصوت 28% ثم النوم بعد المدة (مثال: sleep rain 15min)\n"
                "• messi - تشغيل فيديو Messi.mp4 بصوت 16% في وضع ملء الشاشة والتكرار\n"
                "• sound <0-100> - ضبط مستوى صوت الحاسوب (مثال: sound 25 أو sound 40)\n"
                "• stop (أو cancel) - إغلاق مشغل الفيديو وإلغاء أي موقت مجدول\n"
                "• sleep (أو 'نوم') - وضع السكون المباشر\n"
                "• hibernate (أو 'سبات') - الإسبات\n"
                "• shutdown (أو 'طفي') - إيقاف التشغيل (10 ثوان)\n"
                "• restart (أو 'إعادة تشغيل') - إعادة التشغيل\n"
                "• lock (أو 'قفل') - قفل الشاشة\n"
                "• mute (أو 'كتم') - تبديل كتم الصوت\n"
                "• status (أو 'حالة') - فحص الاتصال ومعلومات الجهاز ومستوى الصوت\n"
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
