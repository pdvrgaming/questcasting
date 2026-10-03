# QuestCast &bull; Meta Quest 2 Low-Latency Screen Casting

**QuestCast** is a complete, production-grade Android application designed specifically for the Meta Quest 2 (running Android-based Quest OS / Android 12L / API 32). It captures the Quest headset's real-time display and streams it with sub-second latency over a local 5 GHz Wi-Fi network directly to standard web browsers on tablets, smartphones, laptops, and smart TVs.

**100% Offline & LAN-Only**: Zero cloud servers, zero public STUN/TURN, zero external CDN dependencies, and zero internet connection required at runtime.

---

## Architecture Overview

```
                      +------------------------------------+
                      |            META QUEST 2            |
                      |                                    |
                      |    [ Quest VR Display / System ]   |
                      |                 |                  |
                      |                 v                  |
                      |     Android MediaProjection        |
                      |                 |                  |
                      |                 v                  |
                      |        VirtualDisplay /            |
                      |    SurfaceTexture (EGL Base)       |
                      |                 |                  |
                      |                 v                  |
                      |   Hardware H.264 Video Encoder     |
                      |     (Qualcomm Snapdragon XR2)      |
                      |                 |                  |
                      |                 v                  |
                      |      Native WebRTC VideoTrack      |
                      |      (Host LAN ICE Candidates)     |
                      |                 |                  |
                      |   +----------------------------+   |
                      |   | Embedded LAN HTTP Server   |   |
                      |   | (Port 8080 - Web Receiver) |   |
                      |   +----------------------------+   |
                      |   | Embedded WebSocket Server  |   |
                      |   | (Port 8088 - Signaling)    |   |
                      |   +----------------------------+   |
                      +-----------------+------------------+
                                        |
                                        | Local 5 GHz Wi-Fi Router
                                        | (Zero Internet / Isolated LAN)
                                        |
                      +-----------------v------------------+
                      |       BROWSER RECEIVER DEVICE       |
                      |  (Smart TV, Tablet, Phone, Laptop) |
                      |                                    |
                      |  1. HTTP GET http://<QUEST_IP>:8080|
                      |     -> Loads index.html + CSS + JS |
                      |                                    |
                      |  2. WebSocket ws://<QUEST_IP>:8088 |
                      |     <-> Exchanges SDP Offer/Answer |
                      |     <-> Exchanges Host LAN ICE     |
                      |                                    |
                      |  3. Direct WebRTC PeerConnection   |
                      |     -> Hardware-accelerated H.264  |
                      |     -> <video autoplay playsinline>|
                      |     -> Real-time HUD FPS & Bitrate |
                      +------------------------------------+
```

---

## Key Features

1. **Hardware-Accelerated Screen Capture Pipeline**:
   - Uses Android `MediaProjection` anchored to a persistent `ForegroundService` with `FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION`.
   - Frames are rendered directly onto an OpenGL ES texture surface managed by Google's native WebRTC `ScreenCapturerAndroid` and hardware `DefaultVideoEncoderFactory`.
   - Zero unnecessary CPU buffer copies or Bitmap conversions.

2. **Ultra-Low Latency Video Streaming**:
   - **Resolution**: 1280 &times; 720 (720p) @ 30 FPS initial target (scalable to 1080p).
   - **Bitrate**: Adaptive 4.0 Mbps target (2.0 Mbps min, 8.0 Mbps max) tuned for 5 GHz Wi-Fi.
   - **Profile**: H.264 Constrained Baseline (`profile-level-id=42e01f`) with zero-latency encoding mode (`realtime` preset, intra-refresh) for broad browser compatibility.

3. **Autonomous Embedded Signaling & Web Server**:
   - Multi-threaded HTTP Server on port `8080` serves the standalone receiver web app.
   - High-throughput WebSocket server on port `8088` handles the SDP offer/answer handshake and host LAN ICE candidate exchange.

4. **Zero-Dependency Vanilla Browser Receiver**:
   - Self-contained web client (`index.html`, `style.css`, `app.js`, `favicon.svg`) embedded inside the APK assets.
   - Standard browser WebRTC API (`RTCPeerConnection`).
   - Features: Real-time HUD stats (resolution, FPS, bitrate), auto-reconnect, responsive layout for phones/tablets/desktops, and TV-friendly fullscreen mode.

5. **Headset User Experience**:
   - Jetpack Compose VR-ready dark UI with one-tap "Start Casting" / "Stop Casting".
   - Dynamic LAN IP detection prioritizing active `wlan0` interface.
   - Live on-screen QR Code (generated locally via ZXing) for instant mobile camera pairing.
   - Real-time diagnostic metrics: client connection status, active resolution, and pipeline state.

---

## Project Structure

```
QuestCast/
├── app/
│   ├── build.gradle.kts                   # AGP 9.0.1, Kotlin 2.3.20, WebRTC 1.3.8
│   ├── src/
│   │   ├── main/
│   │   │   ├── AndroidManifest.xml        # Meta Quest compatibility & permissions
│   │   │   ├── assets/receiver/           # Embedded offline receiver web application
│   │   │   │   ├── app.js                 # Vanilla WebRTC client & metrics polling
│   │   │   │   ├── favicon.svg            # Standalone SVG brand icon
│   │   │   │   ├── index.html             # Receiver HTML5 UI
│   │   │   │   └── style.css              # Dark glassmorphic responsive styling
│   │   │   ├── java/com/questcast/app/
│   │   │   │   ├── MainActivity.kt        # UI host & MediaProjection permission flow
│   │   │   │   ├── model/
│   │   │   │   │   └── CastModels.kt      # State, config, and signaling JSON models
│   │   │   │   ├── server/
│   │   │   │   │   ├── HttpServer.kt      # Embedded HTTP asset server (Port 8080)
│   │   │   │   │   └── SignalingServer.kt # Embedded WebSocket server (Port 8088)
│   │   │   │   ├── service/
│   │   │   │   │   └── CastService.kt     # Foreground service orchestrating pipeline
│   │   │   │   ├── ui/
│   │   │   │   │   └── MainScreen.kt      # Jetpack Compose headset UI & QR code
│   │   │   │   ├── util/
│   │   │   │   │   ├── AppLogger.kt       # Resilient logger wrapper
│   │   │   │   │   ├── NetworkUtils.kt    # LAN IPv4 interface detection
│   │   │   │   │   └── QrCodeGenerator.kt # ZXing QR bitmap generator
│   │   │   │   └── webrtc/
│   │   │   │       ├── AudioCaptureManager.kt # Audio provider & Quest OS isolation
│   │   │   │       └── WebRtcManager.kt   # WebRTC peer connection, capturer, SDP
│   │   └── test/java/com/questcast/app/   # 20 Automated Unit & Integration Tests
│   │       ├── CastModelsAndConfigTest.kt
│   │       ├── HttpServerTest.kt
│   │       ├── SignalingMessageTest.kt
│   │       └── SignalingServerTest.kt
├── build.gradle.kts
├── settings.gradle.kts
└── README.md
```

---

## Build Instructions

### Prerequisites
- JDK 17 (e.g. `C:\Users\PDVR gaming\.jdks\jdk-17.0.12+7`)
- Android SDK (API 34/36, Platform Tools with `adb`)

### Building the APK
Run Gradle from the project root:

```powershell
# Set environment paths
$env:JAVA_HOME = "C:\Users\PDVR gaming\.jdks\jdk-17.0.12+7"
$env:ANDROID_HOME = "C:\Users\PDVR gaming\AppData\Local\Android\Sdk"

# Compile and package debug APK
.\gradlew.bat assembleDebug
```

The compiled APK will be located at:
`app/build/outputs/apk/debug/app-debug.apk`

---

## Installation & Deployment (Meta Quest 2)

1. **Enable Developer Mode on Quest 2**:
   - Open the Meta Quest mobile companion app.
   - Go to **Devices** &rarr; **Headset Settings** &rarr; **Developer Settings** &rarr; Enable **Developer Mode**.

2. **Connect Headset via USB-C**:
   - Plug the Quest 2 into your PC.
   - Put on the headset and click **Allow USB Debugging** and check **Always allow from this computer**.

3. **Install the APK via ADB**:
   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

4. **Launch QuestCast**:
   ```bash
   adb shell am start -n com.questcast.app/.MainActivity
   ```
   *(Or open Quest 2 Library &rarr; Filter by **Unknown Sources** &rarr; Tap **QuestCast**)*.

---

## How to Cast (Step-by-Step)

1. Ensure the Quest 2 and receiving device (phone, tablet, laptop, or TV) are connected to the **same Wi-Fi router** (5 GHz strongly recommended).
2. Inside QuestCast on the headset, tap **START CASTING**.
3. In the system popup, accept the **Screen Recording / MediaProjection** permission.
4. The screen will display:
   - Status: `AWAITING RECEIVER`
   - Receiver URL: `http://<QUEST_IP>:8080` (e.g., `http://192.168.1.105:8080`)
   - A scannable QR Code.
5. On your receiving device:
   - **Phone / Tablet**: Scan the QR code with your camera or enter the URL into Chrome/Safari/Edge.
   - **Smart TV / Laptop**: Type `http://<QUEST_IP>:8080` in the browser.
6. The browser connects automatically via WebRTC, and the live Quest 2 display will stream in real time.
7. To finish casting, tap **STOP CASTING** on the Quest 2 headset.

---

## Audio Capture & Quest OS Status

Android 10+ introduced `AudioPlaybackCaptureConfiguration` for recording system audio. However, on Meta Quest OS (Android 12L / v60+), the Oculus VR system compositor and runtime sandbox explicitly restrict third-party non-system apps from capturing raw application audio streams without system signature permissions.

To prevent instability or crashes:
- Audio capture is cleanly isolated behind `AudioCaptureManager.kt` via the `AudioSourceProvider` interface.
- Video streaming operates with dedicated hardware pipelines unaffected by audio restrictions.

---

## Automated Test Results

The project includes an automated test suite verifying all server, networking, and signaling components:

| Test Class | Tests Run | Result | Duration |
|:---|:---:|:---:|:---:|
| `SignalingMessageTest` | 6 | **PASS** | 0.05s |
| `HttpServerTest` | 6 | **PASS** | 0.42s |
| `SignalingServerTest` | 2 | **PASS** | 1.62s |
| `CastModelsAndConfigTest` | 6 | **PASS** | 0.09s |
| **Total Automated Tests** | **20** | **20 / 20 PASS (100%)** | **2.18s** |

---

## Troubleshooting & Tips

- **Cannot connect to URL**: Confirm both devices are connected to the same Wi-Fi SSID and AP isolation / client isolation is disabled in the router settings.
- **Latency / Stuttering**: Ensure the Quest 2 is connected to a 5 GHz Wi-Fi band with minimal channel interference.
- **Firewall Warnings on TV**: Ensure port `8080` (HTTP) and port `8088` (WebSocket) are not blocked by the local router.
