# Photo AI Enhance v1.3

Automated AI photo enhancement for Samsung Galaxy A56, now with PC GPU mode over **Tailscale** (Wi-Fi **or mobile data**) plus phone-only fallback.

## New in v1.3
- **Windows PC server** under `pc-server/`. Uses official Real-ESRGAN ncnn-Vulkan binary for GTX 1650 and model `realesrgan-x4plus`, ×2 output.
- Phone sends the original photo file over encrypted Tailscale VPN, authenticated with a shared secret. PC applies EXIF orientation and returns the enhanced JPEG.
- Phone saves results separately in Pictures/AI Upscaled. Original is not touched.
- Android UI: Tailscale PC address, secret key, test connection, save settings, switch back to phone-only Real-ESRGAN.
- Existing MediaStore change watcher + 15-minute fallback. Android may delay/stop jobs.

## Setup on Windows + phone
1. From GitHub Actions download `PhotoAIEnhance-PC-Server-v1.3`, extract both ZIP layers, and locate `pc-server/start-server.bat`.
2. On PC install and sign into [Tailscale for Windows](https://tailscale.com/download/windows). On Galaxy A56 install and sign into [Tailscale for Android](https://tailscale.com/download/android) **with the same account**.
3. Install [Python 3.11 or 3.12 for Windows](https://www.python.org/downloads/windows/) (enable Add Python to PATH).
4. Double click `pc-server/start-server.bat`. The first run installs Pillow and downloads the official Vulkan engine (~45 MB).
5. Console prints `http://100.x.y.z:8765` and a secret token; enter them in Photo AI Enhance 1.3, save, and test connection. Never post or commit the token.
6. Allow access to **all photographs**, enable automatic processing, and take a photo. You can also enhance the latest photo with the manual button. Ensure PC stays on and doesn't sleep.
7. For mobile data: disconnect Wi-Fi on the phone while keeping Tailscale enabled and test the connection. Do not port-forward TCP 8765.

If Windows Firewall blocks access, run an administrator PowerShell rule scoped to Tailscale private range: `New-NetFirewallRule -DisplayName "Photo AI Tailscale" -Direction Inbound -Action Allow -Protocol TCP -LocalPort 8765 -RemoteAddress 100.64.0.0/10 -Profile Any`.

## Safety / limitations
- The server binds exclusively to the Tailscale IPv4 of the PC. Android only accepts an HTTP URL in the Tailscale CGNAT IP block and port 8765. HTTP is protected by encrypted Tailscale VPN in transit.
- Share the token only with your own phone. PC server_config.json stays local; never upload it to GitHub.
- No unconditional fallback to slow phone processing if PC is offline: background work retries, and an error is recorded. Remove PC address through "Переключить на локальное улучшение" to use the old offline path.
- File size up to 40 MB; output up to 65 MB. The PC server limits the input longer side to 4096 px to fit GPU/host resources.
- Inference on the GTX 1650 may take minutes; end-to-end performance and mobile network reliability have not been measured on the user's devices.
- Background WorkManager jobs can be delayed or time out. On supported Android versions, user should allow unrestricted app battery usage if consistent processing is needed.
- Existing debug APK signatures can differ between GitHub Actions runners; installation over v1.2 may fail. Uninstall v1.2 before installing v1.3 if signature mismatch. Gallery photos remain untouched.
- Current version is a **test/debug build**, not yet device-tested.
- Model and engine license: [Real-ESRGAN](https://github.com/xinntao/Real-ESRGAN), [Real-ESRGAN-ncnn-vulkan](https://github.com/xinntao/Real-ESRGAN-ncnn-vulkan). See original upstream licenses.

## Build
Actions → Build Android APK → successful build → Artifacts:
- `PhotoAIEnhance-v1.3-debug-apk` (contains Android APK)
- `PhotoAIEnhance-PC-Server-v1.3` (contains ZIP of Windows server files)

Created with AI assistance (ChatGPT).
