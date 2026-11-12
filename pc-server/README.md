# Photo AI Enhance PC Server v1.3 — Windows + GTX 1650

Official Real-ESRGAN-ncnn-vulkan GPU engine and bundled realesrgan-x4plus model. The server returns a JPEG at ×2 dimensions, with EXIF orientation normalized.

## First-time setup
1. Install Tailscale on Windows: https://tailscale.com/download/windows and sign in.
2. Install Tailscale on Samsung: https://tailscale.com/download/android, sign in to the **same account** and enable VPN.
3. Install Python 3.11 or 3.12 (64-bit): https://www.python.org/downloads/windows/ (enable Add Python to PATH).
4. Extract the server folder. Double-click **start-server.bat**. It installs Pillow and downloads the official Windows GPU engine (~45 MB) on the first run.
5. The console shows 'Адрес для телефона' (http://100.x.y.z:8765) and 'Ключ подключения'. Enter both into Photo AI Enhance v1.3 on Android, tap 'Сохранить', then 'Проверить соединение'.
6. Photograph with the Samsung Camera or use 'Улучшить последнее фото ещё раз'. Keep the server window and Tailscale running and the PC awake.

Mobile data works away from home when Tailscale is enabled on both devices. No router port forwarding. Both original and enhanced images consume mobile traffic.

## Security
- Server binds only to the PC's Tailscale IPv4 (100.64.0.0/10), never 0.0.0.0 or public internet.
- Each request requires a randomly generated secret from server_config.json. Never commit/share this file.
- HTTP travels inside Tailscale's encrypted VPN. Do not forward TCP 8765 on your router.
- Uploads limited to 40MB and one GPU request at a time.
- For blocked connections, on PC run an elevated PowerShell **only if necessary**:

```powershell
New-NetFirewallRule -DisplayName "Photo AI Tailscale" -Direction Inbound -Action Allow -Protocol TCP -LocalPort 8765 -RemoteAddress 100.64.0.0/10 -Profile Any
```

## Notes
- GTX 1650 (4GB VRAM): Vulkan tile size 128, single processing thread and model realesrgan-x4plus.
- Large input photos limited to 4096 px long edge. Inference can take minutes depending on image.
- On the A56, Android background work can be delayed or stopped. Keep the app open for a test.
- This software has not been tested on your actual Windows computer.
- The official engine is downloaded from GitHub's Real-ESRGAN v0.2.5.0 release. Model/engine licenses remain with upstream owners.
- No stable Android release-signing keystore is configured; GitHub debug builds may require uninstalling older v1.2.

Created with AI assistance (ChatGPT).
