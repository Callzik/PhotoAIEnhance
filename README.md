# Photo AI Enhance v1.0
Android app for Samsung Galaxy A56. Automatically scans DCIM/Camera with WorkManager approximately every 15 minutes (Android may delay it) and saves 2x images to Pictures/AI Upscaled.

**Important:** v1.0 currently performs standard bilinear scaling, NOT neural super-resolution. ONNX model integration is planned; no model is bundled. Input images may be sampled down to 2048 pixels on the longer side for memory safety.

## Build
GitHub Actions > Build Android APK > Run workflow, or push to main. Download artifact PhotoAIEnhance-v1.0-debug-apk after success.

Built with AI assistance (ChatGPT).