# Photo AI Enhance v1.2

Android photo-enhancement app for Samsung Galaxy A56.

## What's new
- Replaced the subtle ESPCN x2 neural network with **Real-ESRGAN General x4v3** for perceptual restoration.
- The model produces 4x RGB output, reduced to 2x final output (2x in both dimensions).
- Increased max input longest side from the v1.1 2048-pixel power-of-two sampling to **3072 pixels**. This preserves more source detail. For a 4000x3000 photo, output is approximately 6144x4608.
- You can process the **latest camera photo again** with the main button to compare results even if it was already processed by older app versions.
- Photos save as `Pictures/AI Upscaled/AI_v12_<id>_<timestamp>.jpg`, originals untouched.
- Orientation correction based on EXIF remains.
- Automatic new-photo observation uses WorkManager content URI triggers, plus 15-minute fallback. Android can delay or block background work.

## Limitations
- **Quality mode can take minutes or longer**, depending on image size, thermals, battery level, and CPU speed. Exact speed on the Galaxy A56 has not been measured.
- Smartphone AI enhancement can add invented textures and over-sharpen some faces. Keep originals.
- Very large images are capped at 3072 input px on the long edge for memory safety.
- Android may stop long-running background work. Reopen the app to inspect errors.
- This is debug signed on GitHub-hosted runners with a temporary keystore. **To install v1.2, you may need to uninstall v1.1 first** due to signature mismatch. To provide seamless updates later, set up a stable release signing key in private repository secrets.
- No device testing has been done yet.

## Building
GitHub Actions -> Build Android APK -> latest green build -> Artifacts -> PhotoAIEnhance-v1.2-debug-apk.

Workflow downloads a pinned ONNX model into `app/src/main/assets/realesr-general-x4v3.onnx` before compilation and verifies SHA256 `1940a93ee08283a0a7286183186357b1688fe9fa8ede74604b424586aaddf112`.
For local offline builds, put that ONNX model in the same assets directory.

## Model
- Original architecture/weights: [Real-ESRGAN by Xintao Wang](https://github.com/xinntao/Real-ESRGAN) (BSD-3-Clause).
- ONNX conversion: [CoderViking/realesr-general-x4v3-onnx](https://huggingface.co/CoderViking/realesr-general-x4v3-onnx), derived from the original release.
- Input NCHW RGB float32 [0,1]; output 4x RGB. Tiled CPU inference via ONNX Runtime Android.
- The Android code uses the model to restore and enlarge RGB image content, then downsamples its 4x output to 2x for a balanced result.

Created with AI assistance (ChatGPT).