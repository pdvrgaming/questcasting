#!/usr/bin/env python3
"""
QuestCast Automated Headset Installer & Configurator
===================================================
Installs QuestCast onto any connected Meta Quest headsets, enables wireless ADB,
configures necessary permissions (Audio, Usage Stats for Game Audit Logging),
and displays live casting URLs.

Usage:
    python install.py
    python install.py --loop         (Keep running to plug in headset 1, then headset 2, etc.)
    python install.py --connect <ip> (Connect to a headset over Wi-Fi)
"""

import sys
import os
import time
import subprocess
import shutil
import re
import argparse
from pathlib import Path

# Ensure Windows terminal doesn't crash on character encodings
if hasattr(sys.stdout, "reconfigure"):
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

# ANSI colors for nice terminal output
CYAN = "\033[96m"
GREEN = "\033[92m"
YELLOW = "\033[93m"
RED = "\033[91m"
BOLD = "\033[1m"
DIM = "\033[2m"
RESET = "\033[0m"

PACKAGE_NAME = "com.questcast.app"
APP_DIR = Path(__file__).resolve().parent
APK_PATH = APP_DIR / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"


def find_adb():
    """Locate adb.exe automatically."""
    # 1. System PATH
    adb_which = shutil.which("adb")
    if adb_which:
        return adb_which

    # 2. Standard Windows Android SDK paths
    local_app_data = os.environ.get("LOCALAPPDATA", "")
    candidates = [
        Path(local_app_data) / "Android" / "Sdk" / "platform-tools" / "adb.exe",
        Path(os.path.expanduser("~")) / "AppData" / "Local" / "Android" / "Sdk" / "platform-tools" / "adb.exe",
    ]
    for c in candidates:
        if c.exists():
            return str(c)

    return "adb"


ADB = find_adb()


def run_cmd(cmd, check=False, timeout=30):
    """Run shell command and return stdout string."""
    try:
        res = subprocess.run(
            cmd,
            shell=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            timeout=timeout,
            encoding="utf-8",
            errors="replace"
        )
        return res.returncode, res.stdout.strip(), res.stderr.strip()
    except subprocess.TimeoutExpired:
        return -1, "", "Command timed out"
    except Exception as e:
        return -1, "", str(e)


def run_adb(args, serial=None, timeout=60):
    """Run an ADB command targeting a specific device serial if given."""
    if serial:
        cmd = f'"{ADB}" -s {serial} {args}'
    else:
        cmd = f'"{ADB}" {args}'
    return run_cmd(cmd, timeout=timeout)


def ensure_apk_exists():
    """Check if app-debug.apk exists; if not, build it using gradlew."""
    if APK_PATH.exists():
        return True

    print(f"\n{YELLOW}[!] APK not found at {APK_PATH}{RESET}")
    print(f"{CYAN}[*] Building debug APK with Gradle... Please wait.{RESET}")
    gradlew = APP_DIR / ("gradlew.bat" if sys.platform == "win32" else "gradlew")
    if not gradlew.exists():
        print(f"{RED}[-] Error: gradlew not found in {APP_DIR}{RESET}")
        return False

    code, out, err = run_cmd(f'"{gradlew}" assembleDebug', timeout=180)
    if code != 0 or not APK_PATH.exists():
        print(f"{RED}[-] Build failed:{RESET}\n{err}")
        return False

    print(f"{GREEN}[+] Build successful!{RESET}\n")
    return True


def auto_discover_wifi_devices():
    """Scan local subnet for any headsets with wireless ADB enabled on port 5555."""
    import socket
    import concurrent.futures

    local_ip = "192.168.0.1"
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        local_ip = s.getsockname()[0]
        s.close()
    except Exception:
        pass

    parts = local_ip.split(".")
    if len(parts) != 4:
        return []
    base_subnet = f"{parts[0]}.{parts[1]}.{parts[2]}."

    def check_ip(ip):
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.settimeout(0.35)
        try:
            if s.connect_ex((ip, 5555)) == 0:
                return ip
        except Exception:
            pass
        finally:
            s.close()
        return None

    candidates = [f"{base_subnet}{i}" for i in range(1, 255)]
    found = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=35) as ex:
        for res in ex.map(check_ip, candidates):
            if res:
                found.append(res)
    return found


def get_connected_devices(try_wifi_discovery=True):
    """Return list of connected device serials (excluding unauthorized/offline)."""
    code, out, err = run_adb("devices -l")
    devices = []
    unauthorized_count = 0
    for line in out.splitlines():
        line = line.strip()
        if not line or line.startswith("List of devices"):
            continue
        parts = line.split()
        if len(parts) >= 2:
            serial = parts[0]
            status = parts[1]
            if status == "device":
                model_match = re.search(r'model:(\S+)', line)
                model = model_match.group(1) if model_match else "Android Device"
                devices.append({"serial": serial, "model": model, "raw": line})
            elif status == "unauthorized":
                unauthorized_count += 1
                print(f"{YELLOW}[!] Device {serial} is unauthorized. Put on the headset and click 'Always Allow from this computer'.{RESET}")

    # If no devices found via USB, auto-scan Wi-Fi for wireless ADB on port 5555
    if not devices and unauthorized_count == 0 and try_wifi_discovery:
        wifi_ips = auto_discover_wifi_devices()
        if wifi_ips:
            for wip in wifi_ips:
                print(f"{CYAN}[*] Auto-detected wireless Quest on {wip}:5555, connecting...{RESET}")
                run_adb(f"connect {wip}:5555")
            # Re-read devices
            return get_connected_devices(try_wifi_discovery=False)

    # Check Windows PnP if a Quest is plugged in but in Oculus Link mode (PID 5010)
    if not devices and unauthorized_count == 0 and sys.platform == "win32":
        code_pnp, out_pnp, _ = run_cmd('powershell -NoProfile -Command "Get-PnpDevice -InstanceId \'*VID_2833*\' -PresentOnly | Select-Object -ExpandProperty InstanceId"')
        if "PID_5010" in out_pnp:
            dev_serial_match = re.search(r'VID_2833&PID_5010\\([A-Za-z0-9_-]+)', out_pnp)
            dev_info = f" ({dev_serial_match.group(1)})" if dev_serial_match else ""
            print(f"\n{BOLD}{YELLOW}[!] Meta Quest Headset{dev_info} is plugged in, but in 'Oculus Link' mode (PID 5010)!{RESET}")
            print(f"{CYAN}[*] Quick 10-Second Fix in VR:{RESET}")
            print(f"    1. Put on your Quest 2 headset.")
            print(f"    2. In VR, if an 'Enable Oculus Link' popup appears, click {BOLD}'Cancel'{RESET} (or 'Disable Link').")
            print(f"    3. When the 'Allow USB debugging?' prompt appears, check {BOLD}'Always allow'{RESET} and click {GREEN}{BOLD}'Allow'{RESET}.")
            print(f"    4. (If no prompt appears, unplug and re-plug the USB cable while wearing the headset).\n")

    return devices


def get_device_ip(serial):
    """Retrieve the device's Wi-Fi IP address."""
    # Method 1: ip route
    _, out, _ = run_adb("shell ip route", serial=serial)
    for line in out.splitlines():
        if "wlan0" in line and "src" in line:
            parts = line.split()
            if "src" in parts:
                idx = parts.index("src")
                if idx + 1 < len(parts):
                    return parts[idx + 1]

    # Method 2: ip addr show wlan0
    _, out, _ = run_adb("shell ip addr show wlan0", serial=serial)
    match = re.search(r'inet\s+(\d+\.\d+\.\d+\.\d+)/', out)
    if match:
        return match.group(1)

    # Method 3: ifconfig wlan0
    _, out, _ = run_adb("shell ifconfig wlan0", serial=serial)
    match = re.search(r'inet addr:(\d+\.\d+\.\d+\.\d+)', out)
    if match:
        return match.group(1)

    return None


def get_device_battery(serial):
    """Get battery percentage of headset."""
    _, out, _ = run_adb("shell dumpsys battery", serial=serial)
    match = re.search(r'level:\s*(\d+)', out)
    return match.group(1) if match else "Unknown"


def install_and_configure_headset(device):
    """Install QuestCast APK and configure permissions on the given device."""
    serial = device["serial"]
    model = device["model"]
    print(f"\n{BOLD}{CYAN}{'='*60}{RESET}")
    print(f"{BOLD}{CYAN}>>> Configuring Headset: {GREEN}{model}{CYAN} ({serial}){RESET}")
    print(f"{BOLD}{CYAN}{'='*60}{RESET}")

    # 1. Check IP address
    ip = get_device_ip(serial)
    battery = get_device_battery(serial)
    print(f"[*] Battery Level: {YELLOW}{battery}%{RESET}")
    if ip:
        print(f"[*] Wi-Fi IP Address: {GREEN}{ip}{RESET}")
    else:
        print(f"{YELLOW}[!] Wi-Fi IP not detected (ensure headset is connected to Wi-Fi){RESET}")

    # 2. Install APK (Done first before any port switches)
    print(f"[*] Installing {APK_PATH.name}...")
    for attempt in range(1, 4):
        code, out, err = run_adb(f'install -r "{APK_PATH}"', serial=serial, timeout=90)
        if "Success" in out:
            print(f"{GREEN}[+] Installation Successful!{RESET}")
            break
        elif "device" in err.lower() or "not found" in err.lower() or "offline" in err.lower():
            print(f"{YELLOW}[!] Device busy or re-enumerating, waiting (attempt {attempt}/3)...{RESET}")
            run_adb("wait-for-device", serial=serial, timeout=10)
            time.sleep(1.5)
        else:
            print(f"{RED}[-] Install result: {out} {err}{RESET}")
            if attempt == 3:
                return False

    # 3. Grant Runtime Permissions (Avoids VR popups)
    print(f"[*] Auto-granting permissions...")
    run_adb(f"shell pm grant {PACKAGE_NAME} android.permission.RECORD_AUDIO", serial=serial)
    run_adb(f"shell pm grant {PACKAGE_NAME} android.permission.POST_NOTIFICATIONS", serial=serial)
    run_adb(f"shell appops set {PACKAGE_NAME} GET_USAGE_STATS allow", serial=serial)
    print(f"{GREEN}[+] Permissions granted (Mic + Audit Log Usage Stats).{RESET}")

    # 4. Launch App
    print(f"[*] Launching QuestCast...")
    run_adb(f"shell monkey -p {PACKAGE_NAME} -c android.intent.category.LAUNCHER 1", serial=serial)

    # 5. Enable Wireless ADB (port 5555) at the very end
    if ":" not in serial:
        print(f"[*] Enabling Wireless ADB port (5555)...")
        run_adb("tcpip 5555", serial=serial)
        time.sleep(1.0)
        print(f"{GREEN}[+] Wireless ADB enabled!{RESET}")

    # 6. Display Casting URLs
    print(f"\n{BOLD}{GREEN}[OK] Headset Configured and Ready to Cast!{RESET}")
    if ip:
        print(f"  {BOLD}Direct Headset URL:{RESET}  {CYAN}https://{ip}:8443/{RESET}")
        print(f"  {BOLD}Mobile PWA Hub URL:{RESET}  {CYAN}https://pdvrgaming.github.io/questcasting/?ip={ip}{RESET}")
        print(f"  {DIM}(Tip: Opening the Mobile PWA Hub auto-connects to this headset instantly!){RESET}")
    else:
        print(f"  {YELLOW}Check Wi-Fi in headset settings to find its IP address.{RESET}")

    return True


def connect_wifi_ip(ip):
    """Attempt wireless ADB connection to an IP."""
    if not ip:
        return False
    target = f"{ip}:5555" if ":" not in ip else ip
    print(f"[*] Connecting wireless ADB to {target}...")
    code, out, _ = run_adb(f"connect {target}")
    print(f"    {out}")
    return "connected" in out.lower()


def main():
    parser = argparse.ArgumentParser(description="QuestCast Automated Multi-Headset Installer")
    parser.add_argument("--loop", "-l", action="store_true", help="Keep running in a loop to plug and install multiple headsets one by one")
    parser.add_argument("--connect", "-c", type=str, help="Connect to headset IP over Wi-Fi before installing (e.g. <ip-address>)")
    args = parser.parse_args()

    print(f"{BOLD}{CYAN}")
    print("+==========================================================+")
    print("|          QuestCast Automated Headset Installer           |")
    print("|        Zero-Configuration Multi-Device Deployment        |")
    print("+==========================================================+")
    print(f"{RESET}")
    print(f"[*] Using ADB: {DIM}{ADB}{RESET}")
    print(f"[*] Target APK: {DIM}{APK_PATH}{RESET}\n")

    run_adb("start-server")

    if not ensure_apk_exists():
        sys.exit(1)

    if args.connect:
        connect_wifi_ip(args.connect)

    installed_serials = set()

    def process_connected():
        devices = get_connected_devices()
        newly_installed = 0
        for dev in devices:
            serial = dev["serial"]
            if serial not in installed_serials:
                success = install_and_configure_headset(dev)
                if success:
                    installed_serials.add(serial)
                    newly_installed += 1
        return len(devices), newly_installed

    # First pass
    count, installed = process_connected()

    if not args.loop and count > 0:
        print(f"\n{BOLD}{GREEN}[OK] Finished installing on {installed} connected headset(s).{RESET}")
        print(f"{CYAN}To install on additional headsets with one cable, run:{RESET} {BOLD}python install.py --loop{RESET}\n")
        return

    # Loop or Waiting Mode
    if count == 0:
        print(f"\n{YELLOW}[?] No Quest headsets detected yet.{RESET}")
        print(f"{CYAN}[*] Quick Troubleshooting Checklist:{RESET}")
        print(f"    1. {BOLD}USB-C Cable:{RESET} Ensure cable is plugged into PC and headset.")
        print(f"    2. {BOLD}Developer Mode:{RESET} Ensure Developer Mode is ON in Meta Quest mobile app.")
        print(f"    3. {BOLD}In-VR Popup:{RESET} Put on headset and click 'Always Allow from this computer'.")
        print(f"    4. {BOLD}Wi-Fi Connection:{RESET} You can connect directly over Wi-Fi: {CYAN}python install.py --connect <IP>{RESET}\n")
        print(f"{DIM}    Waiting for headset connection (Press Ctrl+C to cancel)...{RESET}")

    try:
        while True:
            time.sleep(2)
            devices = get_connected_devices()
            for dev in devices:
                serial = dev["serial"]
                if serial not in installed_serials:
                    install_and_configure_headset(dev)
                    installed_serials.add(serial)
                    print(f"\n{YELLOW}[*] Plug in your NEXT headset, or press Ctrl+C when finished.{RESET}")
    except KeyboardInterrupt:
        print(f"\n\n{GREEN}[OK] Installer exited. Total headsets configured: {len(installed_serials)}{RESET}")


if __name__ == "__main__":
    main()
