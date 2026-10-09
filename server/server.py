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
import re
import base64
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
pending_confirmation = None

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

# Screenshot & Download Paths
SCREENSHOT_EXE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "screenshot.exe")
SCREENSHOTS_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "screenshots")
DOWNLOADS_DIR = os.path.join(os.path.expanduser("~"), "Downloads")

def format_size(num_bytes):
    """Formats bytes into readable string."""
    if num_bytes >= 1024 * 1024 * 1024:
        return f"{num_bytes / (1024 * 1024 * 1024):.2f} GB"
    elif num_bytes >= 1024 * 1024:
        return f"{num_bytes / (1024 * 1024):.1f} MB"
    elif num_bytes >= 1024:
        return f"{num_bytes / 1024:.1f} KB"
    else:
        return f"{num_bytes} B"

def take_screenshot():
    """Captures the PC screen and returns the image details."""
    try:
        os.makedirs(SCREENSHOTS_DIR, exist_ok=True)
        filename = f"ss_{int(time.time())}.jpg"
        filepath = os.path.join(SCREENSHOTS_DIR, filename)

        if not os.path.exists(SCREENSHOT_EXE):
            return {
                "status": "error",
                "message": "❌ أداة التقاط الشاشة (screenshot.exe) غير متوفرة."
            }

        res = subprocess.run([SCREENSHOT_EXE, filepath], capture_output=True, text=True,
                             creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)

        if res.returncode == 0 and os.path.exists(filepath):
            # Clean up old screenshots (keep latest 8)
            try:
                files = [os.path.join(SCREENSHOTS_DIR, f) for f in os.listdir(SCREENSHOTS_DIR) if f.startswith("ss_") and f.endswith(".jpg")]
                files.sort(key=os.path.getmtime)
                if len(files) > 8:
                    for old_file in files[:-8]:
                        try:
                            os.remove(old_file)
                        except Exception:
                            pass
            except Exception:
                pass

            file_size = os.path.getsize(filepath)
            return {
                "status": "success",
                "type": "image",
                "image_url": f"/screenshot/{filename}",
                "filename": filename,
                "message": f"📸 تم التقاط لقطة شاشة للحاسوب بنجاح! ({format_size(file_size)})"
            }
        else:
            err_msg = res.stderr.strip() or res.stdout.strip() or "فشل غير معروف"
            return {
                "status": "error",
                "message": f"❌ تعذر التقاط الشاشة: {err_msg}"
            }
    except Exception as e:
        return {
            "status": "error",
            "message": f"❌ خطأ أثناء التقاط الشاشة: {str(e)}"
        }

def execute_action(action_type, param=None):
    """Executes the requested action safely and returns a response message."""
    global active_sleep_timer, active_sleep_details, pending_confirmation
    cmd = action_type.strip().lower()
    
    # Delayed execution helper so response reaches the client before PC sleeps/shuts down
    def run_delayed(func, delay=1.0):
        def worker():
            time.sleep(delay)
            func()
        t = threading.Thread(target=worker, daemon=True)
        t.start()

    # Confirmation Handlers (yes / no)
    if cmd in ["yes", "y", "نعم", "ايوه", "ok"]:
        if pending_confirmation and (time.time() - pending_confirmation.get("time", 0) <= 60):
            action_to_do = pending_confirmation["action"]
            pending_confirmation = None
            
            if action_to_do == "sleep":
                def do_sleep():
                    ps_cmd = (
                        "Add-Type -AssemblyName System.Windows.Forms; "
                        "[System.Windows.Forms.Application]::SetSuspendState([System.Windows.Forms.PowerState]::Suspend, $false, $false)"
                    )
                    subprocess.run(["powershell", "-NoProfile", "-Command", ps_cmd],
                                   creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
                run_delayed(do_sleep, 1.2)
                return {
                    "status": "success",
                    "message": "💤 تم تأكيد الأمر! جاري إدخال الحاسوب في وضع السكون (Sleep)... تصبح على خير!"
                }

            elif action_to_do == "shutdown":
                subprocess.run(["shutdown", "/s", "/t", "5", "/c", "Shutdown confirmed from Phone"], 
                               creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
                return {
                    "status": "warning",
                    "message": "🛑 تم تأكيد الأمر! سيتم إيقاف تشغيل الحاسوب بالكامل خلال 5 ثوانٍ."
                }

            elif action_to_do == "restart":
                subprocess.run(["shutdown", "/r", "/t", "5", "/c", "Restart confirmed from Phone"],
                               creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
                return {
                    "status": "warning",
                    "message": "🔄 تم تأكيد الأمر! سيتم إعادة تشغيل الحاسوب خلال 5 ثوانٍ."
                }

            elif action_to_do == "hibernate":
                def do_hibernate():
                    subprocess.run(["shutdown", "/h"], creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
                run_delayed(do_hibernate, 1.2)
                return {
                    "status": "success",
                    "message": "⚡ تم تأكيد الأمر! جاري إدخال الحاسوب في وضع الإسبات (Hibernate)..."
                }
        else:
            pending_confirmation = None
            return {
                "status": "info",
                "message": "ℹ️ لا يوجد أمر معلق بانتظار التأكيد حالياً (أو انتهت صلاحية التأكيد 60 ثانية)."
            }

    elif cmd in ["no", "n", "لا", "رفض"]:
        if pending_confirmation:
            action_name = pending_confirmation["action"]
            pending_confirmation = None
            return {
                "status": "success",
                "message": f"✅ تم إلغاء تنفيذ أمر ({action_name}) بنجاح! حاسوبك بأمان."
            }
        else:
            return {
                "status": "info",
                "message": "ℹ️ لا يوجد أمر معلق لإلغائه."
            }

    # Scheduled Sleep without Rain (e.g. sleep 15, sleep 30, sleep 15m, sleep 1h)
    elif ((cmd.startswith("sleep ") or cmd.startswith("نوم ") or cmd.startswith("سكون "))
          and not cmd.startswith("sleep rain")):
        dur_part = cmd.replace("sleep", "").replace("نوم", "").replace("سكون", "").strip()
        sec = parse_duration_seconds(dur_part, default_sec=15 * 60)
        dur_human = format_duration(sec)

        # Cancel any previous sleep timer
        if active_sleep_timer:
            try:
                active_sleep_timer.cancel()
            except Exception:
                pass
            active_sleep_timer = None

        def on_timed_sleep():
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

        active_sleep_timer = threading.Timer(sec, on_timed_sleep)
        active_sleep_timer.daemon = True
        active_sleep_timer.start()
        active_sleep_details = {"name": "Scheduled Sleep", "seconds": sec, "text": dur_human, "start": time.time()}

        return {
            "status": "success",
            "message": (
                f"💤 تم جدولة وضع السكون (Sleep) بنجاح!\n\n"
                f"⏱️ سينام الحاسوب تلقائياً بعد: {dur_human}.\n"
                f"💡 أرسل 'cancel' لإلغاء الموقت في أي وقت."
            )
        }

    # Immediate critical actions that require confirmation
    elif cmd in ["sleep", "نوم", "سكون", "suspend"]:
        pending_confirmation = {"action": "sleep", "time": time.time()}
        return {
            "status": "warning",
            "message": (
                "⚠️ تأكيد أمان:\nهل تريد إدخال الحاسوب في وضع السكون (Sleep) الآن؟\n\n"
                "👉 أرسل **yes** أو **نعم** للتأكيد.\n"
                "👉 أرسل **no** أو **لا** للإلغاء.\n"
                "⏳ مهلة التأكيد: 60 ثانية."
            )
        }

    elif cmd in ["shutdown", "طفي", "إيقاف", "إطفاء"]:
        pending_confirmation = {"action": "shutdown", "time": time.time()}
        return {
            "status": "warning",
            "message": (
                "⚠️ تأكيد أمان:\nهل أنت متأكد من رغبتك في إيقاف تشغيل الحاسوب (Shutdown) بالكامل؟\n\n"
                "👉 أرسل **yes** أو **نعم** للتأكيد.\n"
                "👉 أرسل **no** أو **لا** للإلغاء.\n"
                "⏳ مهلة التأكيد: 60 ثانية."
            )
        }

    elif cmd in ["restart", "reboot", "إعادة تشغيل"]:
        pending_confirmation = {"action": "restart", "time": time.time()}
        return {
            "status": "warning",
            "message": (
                "⚠️ تأكيد أمان:\nهل تريد بالتأكيد إعادة تشغيل الحاسوب (Restart)؟\n\n"
                "👉 أرسل **yes** أو **نعم** للتأكيد.\n"
                "👉 أرسل **no** أو **لا** للإلغاء.\n"
                "⏳ مهلة التأكيد: 60 ثانية."
            )
        }

    elif cmd in ["hibernate", "سبات"]:
        pending_confirmation = {"action": "hibernate", "time": time.time()}
        return {
            "status": "warning",
            "message": (
                "⚠️ تأكيد أمان:\nهل تريد إدخال الحاسوب في وضع الإسبات (Hibernate) الآن؟\n\n"
                "👉 أرسل **yes** أو **نعم** للتأكيد.\n"
                "👉 أرسل **no** أو **لا** للإلغاء.\n"
                "⏳ مهلة التأكيد: 60 ثانية."
            )
        }

    elif (cmd.startswith("sleep rain") or cmd == "sleep rain" or 
          cmd.startswith("rain") or cmd.startswith("sr ") or cmd == "sr" or
          cmd.startswith("srm ") or cmd == "srm"):
        # Cancel any previous sleep timer
        if active_sleep_timer:
            try:
                active_sleep_timer.cancel()
            except Exception:
                pass
            active_sleep_timer = None

        # Check if minimized desktop mode is requested
        is_minimized = (
            "minimized" in cmd or 
            "minimize" in cmd or 
            "min" in cmd or 
            "مصغر" in cmd or 
            "مخفي" in cmd or 
            cmd.startswith("srm")
        )

        # Parse duration (default 15 minutes)
        cleaned = cmd
        for word in ["sleep rain", "rain", "srm", "sr", "minimized", "minimize", "min", "مصغر", "مخفي"]:
            cleaned = cleaned.replace(word, "")
        dur_part = cleaned.strip()

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

        mpv_exec = MPV_PATH if os.path.exists(MPV_PATH) else "mpv"

        if is_minimized:
            # Minimize all open windows so only the black desktop is shown
            subprocess.run(["powershell", "-NoProfile", "-Command", "(New-Object -ComObject Shell.Application).MinimizeAll()"],
                           creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
            
            # Launch mpv minimized in background
            subprocess.Popen([mpv_exec, "--window-minimized=yes", "--loop-file=inf", RAIN_VIDEO],
                             creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
            
            # Sweep again after 0.5s to ensure clean black desktop
            def sweep_minimize():
                time.sleep(0.5)
                subprocess.run(["powershell", "-NoProfile", "-Command", "(New-Object -ComObject Shell.Application).MinimizeAll()"],
                               creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
            threading.Thread(target=sweep_minimize, daemon=True).start()
            mode_desc = "مع تصغير كافة النوافذ (Desktop فقط)"
        else:
            # Standard fullscreen mode
            subprocess.Popen([mpv_exec, "--fs", "--loop-file=inf", RAIN_VIDEO],
                             creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
            mode_desc = "في وضع ملء الشاشة (Fullscreen + Loop)"

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
        active_sleep_details = {"name": "Rain Sleep", "seconds": sec, "text": dur_human, "start": time.time(), "minimized": is_minimized}

        return {
            "status": "success",
            "message": (
                f"🌧️ تم تشغيل Rain.mp4 بصوت 28% {mode_desc}!\n\n"
                f"⏱️ المدة المحددة: {dur_human}\n"
                f"💤 سينام الحاسوب تلقائياً بعد انتهاء الوقت.\n"
                f"💡 أرسل 'cancel' أو 'stop' لإلغاء الموقت وإغلاق الفيديو في أي وقت."
            )
        }

    elif cmd in ["messi", "ميسي", "m", "messi mp4", "messi.mp4"]:
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
          cmd.startswith("volume") or cmd.startswith("صوت") or
          cmd.startswith("s ") or cmd == "s" or bool(re.match(r"^s\d+$", cmd))):
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

    elif cmd in ["screenshot", "ss", "screen", "capture", "لقطة", "لقطة شاشة", "شاشة", "صورة الشاشة"]:
        return take_screenshot()

    else:
        return {
            "status": "unknown",
            "message": (
                f"❓ أمر غير معروف: '{action_type}'\n\n"
                "📌 الأوامر المدعومة:\n"
                "• screenshot (أو ss) - التقاط لقطة شاشة للحاسوب وإرسالها للمحادثة فوراً\n"
                "• إرسال ملفات/صور - أرسل أي ملف ليحفظ مباشرة في مجلد Downloads\n"
                "• sleep rain <مدة> - تشغيل Rain.mp4 في وضع ملء الشاشة (مثال: sleep rain 15min)\n"
                "• sleep rain <مدة> min - تشغيل Rain.mp4 مع تصغير كل النوافذ ليبقى الديسكتوب فقط\n"
                "• s <0-100> (أو sound) - ضبط مستوى صوت الحاسوب (مثال: s 15 أو s 40)\n"
                "• messi (أو m) - تشغيل فيديو Messi.mp4 بصوت 16% في وضع ملء الشاشة والتكرار\n"
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
        self.send_header("Access-Control-Allow-Headers", "Content-Type, X-Filename, X-Filename-B64")

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
        elif parsed.path.startswith("/screenshot/"):
            filename = os.path.basename(parsed.path.replace("/screenshot/", "").strip())
            filepath = os.path.join(SCREENSHOTS_DIR, filename)
            if os.path.exists(filepath) and os.path.isfile(filepath):
                try:
                    file_size = os.path.getsize(filepath)
                    self.send_response(200)
                    self._send_cors_headers()
                    self.send_header("Content-Type", "image/jpeg")
                    self.send_header("Content-Length", str(file_size))
                    self.send_header("Cache-Control", "no-cache")
                    self.end_headers()
                    with open(filepath, "rb") as f:
                        while True:
                            chunk = f.read(65536)
                            if not chunk:
                                break
                            self.wfile.write(chunk)
                except Exception:
                    pass
            else:
                self.send_response(404)
                self.end_headers()
        else:
            self.send_response(404)
            self.end_headers()

    def do_POST(self):
        parsed = urllib.parse.urlparse(self.path)
        if parsed.path in ["/upload", "/api/upload"]:
            try:
                os.makedirs(DOWNLOADS_DIR, exist_ok=True)
                raw_filename = ""
                
                # Check base64 header first (preserves Arabic and special chars)
                b64_name = self.headers.get("X-Filename-B64")
                if b64_name:
                    try:
                        raw_filename = base64.b64decode(b64_name).decode("utf-8")
                    except Exception:
                        pass
                
                if not raw_filename:
                    raw_name = self.headers.get("X-Filename", "")
                    if raw_name:
                        raw_filename = urllib.parse.unquote(raw_name)

                # Fallback if no filename provided
                if not raw_filename or not raw_filename.strip():
                    content_type = self.headers.get("Content-Type", "")
                    ext = ".bin"
                    if "image/jpeg" in content_type: ext = ".jpg"
                    elif "image/png" in content_type: ext = ".png"
                    elif "application/pdf" in content_type: ext = ".pdf"
                    raw_filename = f"upload_{int(time.time())}{ext}"

                # Sanitize filename (prevent path traversal)
                safe_filename = os.path.basename(raw_filename.strip()).replace("/", "").replace("\\", "")
                if not safe_filename:
                    safe_filename = f"upload_{int(time.time())}.bin"

                # Handle duplicate filenames in Downloads
                base_name, ext = os.path.splitext(safe_filename)
                target_path = os.path.join(DOWNLOADS_DIR, safe_filename)
                counter = 1
                while os.path.exists(target_path):
                    safe_filename = f"{base_name} ({counter}){ext}"
                    target_path = os.path.join(DOWNLOADS_DIR, safe_filename)
                    counter += 1

                content_len = int(self.headers.get("Content-Length", 0))
                bytes_received = 0
                
                with open(target_path, "wb") as out_file:
                    while bytes_received < content_len:
                        chunk_size = min(65536, content_len - bytes_received)
                        chunk = self.rfile.read(chunk_size)
                        if not chunk:
                            break
                        out_file.write(chunk)
                        bytes_received += len(chunk)

                size_str = format_size(bytes_received)
                print(f"[UPLOAD] Saved '{safe_filename}' ({size_str}) to {DOWNLOADS_DIR}")

                response_data = {
                    "status": "success",
                    "filename": safe_filename,
                    "size": bytes_received,
                    "path": target_path,
                    "message": f"📥 تم استلام الملف بنجاح وحفظه في مجلد التنزيلات (Downloads):\n📁 {safe_filename} ({size_str})"
                }
                body = json.dumps(response_data, ensure_ascii=False).encode("utf-8")
                self.send_response(200)
                self._send_cors_headers()
                self.send_header("Content-Type", "application/json; charset=utf-8")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            except Exception as ex:
                err_data = {
                    "status": "error",
                    "message": f"❌ فشل حفظ الملف المرسل: {str(ex)}"
                }
                body = json.dumps(err_data, ensure_ascii=False).encode("utf-8")
                self.send_response(500)
                self._send_cors_headers()
                self.send_header("Content-Type", "application/json; charset=utf-8")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

        elif parsed.path in ["/command", "/api/command", "/"]:
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
