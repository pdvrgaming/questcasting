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
        Path("C:/Users/PDVR gaming/AppData/Local/Android/Sdk/platform-tools/adb.exe"),
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


def get_connected_devices():
    """Return list of connected device serials (excluding unauthorized/offline)."""
    code, out, err = run_adb("devices -l")
    devices = []
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
                print(f"{YELLOW}[!] Device {serial} is unauthorized. Put on the headset and click 'Allow USB Debugging'.{RESET}")
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

    # 2. Enable Wireless ADB (port 5555)
    print(f"[*] Enabling Wireless ADB port (5555)...")
    code, out, _ = run_adb("tcpip 5555", serial=serial)
    if "restarting in TCP mode" in out or code == 0:
        print(f"{GREEN}[+] Wireless ADB enabled!{RESET}")

    # 3. Install APK
    print(f"[*] Installing {APK_PATH.name}...")
    code, out, err = run_adb(f'install -r "{APK_PATH}"', serial=serial, timeout=90)
    if "Success" in out:
        print(f"{GREEN}[+] Installation Successful!{RESET}")
    else:
        print(f"{RED}[-] Install result: {out} {err}{RESET}")
        return False

    # 4. Grant Runtime Permissions (Avoids VR popups)
    print(f"[*] Auto-granting permissions...")
    # Audio for Push-to-Talk
    run_adb(f"shell pm grant {PACKAGE_NAME} android.permission.RECORD_AUDIO", serial=serial)
    # Notifications
    run_adb(f"shell pm grant {PACKAGE_NAME} android.permission.POST_NOTIFICATIONS", serial=serial)
    # App Usage Stats (For Automatic VR Game Audit Tracking: Beat Saber, Jurassic World, etc.)
    run_adb(f"shell appops set {PACKAGE_NAME} GET_USAGE_STATS allow", serial=serial)
    print(f"{GREEN}[+] Permissions granted (Mic + Audit Log Usage Stats).{RESET}")

    # 5. Launch App
    print(f"[*] Launching QuestCast...")
    run_adb(f"shell monkey -p {PACKAGE_NAME} -c android.intent.category.LAUNCHER 1", serial=serial)

    # 6. Display Casting URLs
    print(f"\n{BOLD}{GREEN}✓ Headset Ready to Cast!{RESET}")
    if ip:
        print(f"  {BOLD}Dashboard URL:{RESET}  {CYAN}http://{ip}:8080/dashboard.html{RESET}")
        print(f"  {BOLD}Secure URL:{RESET}     {CYAN}https://{ip}:8443/dashboard.html{RESET} (Enables Push-to-Talk Mic)")
        print(f"  {DIM}(Tip: Opening either URL on any device auto-detects all other headsets!){RESET}")
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
    parser.add_argument("--connect", "-c", type=str, help="Connect to headset IP over Wi-Fi before installing (e.g. 192.168.0.168)")
    args = parser.parse_args()

    print(f"{BOLD}{CYAN}")
    print("╔══════════════════════════════════════════════════════════╗")
    print("║          QuestCast Automated Headset Installer           ║")
    print("║        Zero-Configuration Multi-Device Deployment        ║")
    print("╚══════════════════════════════════════════════════════════╝")
    print(f"{RESET}")
    print(f"[*] Using ADB: {DIM}{ADB}{RESET}")
    print(f"[*] Target APK: {DIM}{APK_PATH}{RESET}\n")

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
        print(f"\n{BOLD}{GREEN}[✓] Finished installing on {installed} connected headset(s).{RESET}")
        print(f"{CYAN}To install on additional headsets with one cable, run:{RESET} {BOLD}python install.py --loop{RESET}\n")
        return

    # Loop or Waiting Mode
    if count == 0:
        print(f"{YELLOW}[?] No Quest headsets detected yet.{RESET}")
        print(f"{CYAN}[*] Connect your Quest headset to your PC with a USB-C cable (or turn on Wi-Fi).{RESET}")
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
        print(f"\n\n{GREEN}[✓] Installer exited. Total headsets configured: {len(installed_serials)}{RESET}")


if __name__ == "__main__":
    main()
