# Photo AI Enhance v1.1

Android app for Samsung Galaxy A56 (Android 10+).

## Features
- Real neural 2x super-resolution with **ESPCN x2**, via ONNX Runtime on-device.
- Keeps portrait / landscape / mirrored EXIF orientation on output.
- Watches MediaStore for new photos using WorkManager content URI triggers, with 15-minute periodic scan fallback.
- Stores enhanced images in **Pictures/AI Upscaled**. Original photos stay untouched.
- Offline photo processing. The model is packaged in the APK by GitHub Actions.

## Limitations
- Android background restrictions mean that triggering immediately after each photo is **not guaranteed**.
- Large input photos are downsampled to at most ~2048 px on the long edge before ESPCN to avoid exhausting memory. Therefore "x2" is relative to the input actually passed to the model, and may not be 2x the original sensor image.
- ESPCN is a small luminance super-resolution network. It cannot reconstruct real information that was never captured.
- v1.0 GitHub Actions debug APK used an ephemeral signing key. v1.1 may require **uninstalling v1.0** before installation; this can erase app settings, but photos in the gallery remain. Do not delete originals. For stable future updates, configure persistent signing secrets instead of GitHub Actions debug signing.
- This build is not device-tested; please report crashes and logs.

## Build
Go to Actions > Build Android APK > Run workflow. After a successful run download artifact `PhotoAIEnhance-v1.1-debug-apk`.

GitHub Actions fetches the pinned Apache-2.0 model from `clibdev/ESPCN-PyTorch` release 1.0.0 and verifies SHA-256 before building. For an offline local Android Studio build, place the exact checked model in `app/src/main/assets/espcn-x2.onnx` first.

## Model attribution
ESPCN PyTorch / ONNX model weights from [clibdev/ESPCN-PyTorch](https://github.com/clibdev/ESPCN-PyTorch), Apache License 2.0. Original project by Lornatang, derived from ESPCN research.

Created with AI assistance (ChatGPT).