# Third-party components

- **HT-Demucs** — Meta Platforms, Inc.; MIT. https://github.com/facebookresearch/demucs
- **HT-Demucs 6s ONNX conversion** — StemSplit; MIT. https://github.com/StemSplit/demucs-onnx ; https://huggingface.co/adowu/htdemucs-6s-onnx
- **ONNX Runtime Android 1.23.2** — Microsoft; MIT. https://github.com/microsoft/onnxruntime
- **AndroidX / Jetpack Compose / Material Icons** — The Android Open Source Project; Apache-2.0. https://android.googlesource.com/platform/frameworks/support/
- **Kotlin / kotlinx.coroutines** — JetBrains and contributors; Apache-2.0. https://github.com/JetBrains/kotlin ; https://github.com/Kotlin/kotlinx.coroutines
- **Backdrop 1.0.0** — Kyant; Apache-2.0. https://github.com/Kyant0/AndroidLiquidGlass
- **Capsule 2.1.1** — Kyant; Apache-2.0. https://github.com/Kyant0/Capsule
- **AHUTong Radiant navigation and gesture components** — OpenAHU/AHUTong contributors; GPL-3.0. https://github.com/OpenAHU/AHUTong-Android ; source revision `0492acc076c81533a8238acd1b796c2c6e0fe488`.
- **Gradle wrapper** — Gradle; Apache-2.0. https://github.com/gradle/gradle

The bottom navigation and its gesture/highlight/material dependencies are adapted from AHUTong under GPL-3.0; attribution is present in each ported source file. The application-specific pages and buttons retain the original Voice Focus implementation. No AHUTong business code is included. The GPL-3.0 text is supplied in LICENSE and assets/licenses/AHUTong-GPL-3.0.txt.

The ONNX model is downloaded separately, pinned to revision `d83893853e32af79f4ddc8d97d7e513eb5058d14`. It is not embedded in the APK. Model identity is verified using SHA-256 before use. Its guitar/piano labels do not establish an independent ukulele source.

License texts are included under `app/src/main/assets/licenses/` and can be read in the app.
