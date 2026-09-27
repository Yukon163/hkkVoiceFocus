# KK Voice Focus

Android 离线音轨分离与混音工具。Kotlin + Jetpack Compose。按钮、配色和页面样式沿用第一版；底部导航直接移植 AHUTong Radiant 的胶囊、按压高光、阻尼拖动、弹簧回弹与折射动画。工程位于 `D:\code\hkkVoiceFocus`。

## 已实现

- 工作室和“我的作品”均提供导入加号，优先调用已安装的 MT 管理器选择音视频；MT 不可用时回退到系统选择器，也可通过其他应用的“分享”导入。
- **弹唱模式**：人声 + 完整伴奏。适用于钢琴、电子琴、吉他和尤克里里弹唱；乐器选择只改变伴奏名称，不会偷偷切换不匹配的模型。
- **多乐器模式**：人声、吉他、钢琴、鼓、贝斯和其他声部，可逐轨调节。
- 每轨 0–200% 音量、精确百分比输入、静音；人声突出、原始比例、人声、伴奏预设。旧工程中的独听标记自动忽略。
- 同步实时试听、拖动定位、真实音频波形、原声对比。音量变化做短渐变，立体声联动限幅避免过载。
- 导出混音 WAV、M4A 或带原画面的 MP4；也可导出包含原始独立音轨的 ZIP。
- 工程、原始文件、浮点 WAV 分轨和配比默认保存在 `Sounds/VoiceFocus/Projects/<工程ID>/`，可从“作品”重新打开或删除；应用私有目录只作为处理工作缓存。
- 默认导出直接写入 `/storage/emulated/0/Sounds/`，也可通过“另存为”选其他位置。
- 首次授权 Sounds 文件夹后会迁移旧工程；重装后重新授权，使用“恢复或同步作品”即可恢复公共工程。
- 模型下载续传、SHA-256 校验和本地导入。下载模型后无需网络，音视频不上传服务器。
- 处理任务通过前台服务继续运行，支持取消；进程被系统终止后，项目会标记为中断并允许重新处理。
- 固定启用 Radiant 材质，Android 8–11 自动降级为染色面板；可选择浅色、深色或跟随系统明暗模式。
- 主题使用固定青蓝色（浅色主色 `#16798A`、深色主色 `#8BD7DF`），不会随系统壁纸改变；原声对比开关的关闭态也采用淡青蓝。

## 模型能力的实际边界

预训练模型是 HT-Demucs 6s，其六个输出为 `drums / bass / other / vocals / guitar / piano`。**没有独立的 ukulele 输出**。尤克里里弹唱可以使用两轨模式来调节人声与完整伴奏；若吉他、尤克里里同时演奏，不能保证分别得到干净的两条乐器轨。声部也可能存在串音和音色损失，尤其是现场混响和钢琴轨。应用内已显示这项限制，没有将“其他声部”假称为尤克里里。

之前桌面脚本使用的 `原音频 − 估计人声` 本来就适用于多种伴奏，并非电子琴专用。本应用保留了这种两轨方式，另提供真实六轨模型结果。要获得可靠的独立尤克里里声部，需要训练/验证包含该标签的分离模型；仅增加一个滑块不能实现。

## 运行

Android Studio 打开项目，使用 JDK 17、Android SDK 36。最低 Android 8.0（API 26），Debug 与 Release 均仅打包 `arm64-v8a`，用于 64 位 ARM 手机或 ARM64 模拟器。

ONNX Runtime 是加载 ONNX 模型并执行推理计算的本地引擎，本应用使用它完成手机端音轨分离。APK 包含它的 ARM64 原生库；模型权重仍在首次使用时另行下载。Release 保持启用 R8 混淆；过滤掉 x86_64 可避免同一 APK 携带第二套大型推理库。

```powershell
adb devices
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n com.hkk.voicefocus/.MainActivity
```

首次进入“设置”下载约 **130 MiB（136,428,532 字节）** 的模型，或选择导入模型文件。分离时模型内存用量远大于下载文件，取决于设备及推理库；建议在有至少约 2 GB 可用内存时处理。音轨采用磁盘存储，长录音不会一次性读入 Java 堆；每分钟六轨及原音轨约占 141 MiB PCM 存储，另需原文件和导出空间。

首次保存工程需通过系统文件选择器授权内部存储的 **Sounds** 文件夹；若不存在，可在选择器中创建。应用使用限定目录的持久 SAF 授权，不申请所有文件管理权限。公共工程采用完整快照、SHA-256 校验和配置备份；取消替换时保留上一份完整快照。公共 WAV 使用 44.1 kHz 双声道 32 位浮点保存分轨精度，导出的普通 WAV 仍为 16 位 PCM。处理时保留私有工作缓存，以支持随机定位和实时混音；卸载应用不会删除 Sounds 中的公共工程。

音视频导入优先使用 MT 的标准 `ACTION_GET_CONTENT` 入口，限定包名 `bin.mt.plus`，不绑定混淆后的 Activity 类名。支持返回的单文件 data URI 或 ClipData；临时读取授权传递给导入服务，文件随即复制到工程。Sounds 目录授权使用 Android 的 `ACTION_OPEN_DOCUMENT_TREE`；“另存为”使用系统 `ACTION_CREATE_DOCUMENT`。

模型固定下载地址：

https://huggingface.co/adowu/htdemucs-6s-onnx/resolve/d83893853e32af79f4ddc8d97d7e513eb5058d14/htdemucs_6s_fp16weights.onnx

SHA-256：`7ce55792e2231c93fbf92de95f5fd5b3a5e6c89f7db690dfd693e8f1dce56869`

## 格式与导出

导入使用 Android MediaExtractor / MediaCodec，常见 MP3、AAC/M4A、PCM WAV、FLAC、Ogg 和 MP4、MKV、WebM 的可用性取决于设备内置解码器。DRM、损坏的文件以及设备不支持的编码会给出错误，不承诺任意扩展名均可解码。

| 导出 | 内容 |
| --- | --- |
| WAV | 44.1 kHz、双声道、16 位 PCM，应用当前混音配比 |
| M4A | AAC-LC 256 kbps，应用当前混音配比 |
| MP4 | 复制原视频画面，音轨改为 AAC-LC 256 kbps；要求视频编码能封装入 MP4 |
| ZIP | 各原始分轨的 WAV，不应用静音/音量设置 |

没有把 AAC 伪装成 MP3，也不依赖已停更的 FFmpegKit。当前未提供 MP3 编码导出。重新封装的视频不包含原音轨；多音轨输入使用第一条音频轨。

## 处理链路

1. 将用户选中的文件复制到项目目录，后续不依赖临时 URI。
2. 解码为浮点 PCM；单声道扩展为双声道，多声道降混；非 44.1 kHz 音频使用带窗 sinc 重采样。
3. ONNX Runtime 在 CPU 上处理固定 343,980 样本（7.8 秒）片段，25% 重叠；加权合成仅保留待重叠的尾部，避免将整段六轨结果留在内存。
4. 两轨模式取模型人声，伴奏逐样本用原音频减去人声；六轨模式将重建残差分配给“其他”，因此全部 100% 时保持原混合信号。
5. AudioTrack 用单一样本时钟读取所有轨进行试听；导出沿用同样的增益和联动限幅策略。
6. MediaCodec 编码 AAC，MediaMuxer 封装 M4A/MP4；默认保存到 Sounds，选择“另存为”时才调用文件选择器。

主要代码位于 `app/src/main/java/com/hkk/voicefocus/`：`audio/` 为解码、混音、播放、导出；`processing/` 为模型管理、分离和服务；`data/` 为项目持久化；`ui/` 为 Compose 界面。

## 验证

2026-09-27 已在 **MEIZU 21 / Android 14** 上运行真实模型。`OfflinePipelineTest` 先用 12 秒、再用完整约 70 秒的真实弹唱视频测试六轨推理、分段边界重建、所有四种导出和 AAC 解码回读；完整测试用时约 209 秒，全部通过。另通过界面验证完整视频的两轨分离、试听和百分比输入。CPU 推理并非实时保证，速度受设备和温度影响。

测试中发现 ORT 默认 CPU arena 会将进程内存撑到约 4.5 GB，并被 Flyme 终止。最终配置关闭 CPU arena 与 memory pattern，使用至多两个推理线程，完整视频复测通过，运行中观测 PSS 约 1.1 GB（非跨机型峰值保证）。

`MediaFormatTest` 在同一设备验证了 48 kHz 单声道 WAV、24 位双声道 WAV、32 kHz 单声道 MP3、48 kHz FLAC、Ogg Opus 和 WebM Opus 的导入、时长与重采样，全部通过。测试素材需要放在 external files 的 `formats/` 目录，文件名见测试类。

五项 JVM 单元测试覆盖配比/残差、重叠边界、立体声限幅、单轨静音和 PCM/WAV I/O。移除独听后已同步调整对应用例；后续修改按用户要求只编译打包，交由用户手测。

真实模型集成测试需要先将文件放入应用 external files 目录（不要把测试音视频和模型提交到 Git）：

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb install -r app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
adb shell mkdir -p /sdcard/Android/data/com.hkk.voicefocus/files
adb push path\to\htdemucs_6s_fp16weights.onnx /sdcard/Android/data/com.hkk.voicefocus/files/test-model.onnx
adb push path\to\12-second-video.mp4 /sdcard/Android/data/com.hkk.voicefocus/files/validation.mp4
adb shell am instrument -w -e class com.hkk.voicefocus.OfflinePipelineTest com.hkk.voicefocus.test/androidx.test.runner.AndroidJUnitRunner
```

模型来源及依赖许可见 `THIRD_PARTY_NOTICES.md`。没有接入云分离服务、账号系统或遥测。

## Radiant 底栏来源

移植自 OpenAHU/AHUTong-Android `0492acc076c81533a8238acd1b796c2c6e0fe488` 的 `LiquidBottomTabs`、`LiquidBottomTab`、`InteractiveHighlight`、`DampedDragAnimation`、`DragGestureInspector` 和材质参数。只适配包名、主题颜色接口和本应用的三个入口；动画参数沿用上游。按用户要求，未保留 Radiant 按钮移植。相关代码和本项目按 GPL-3.0 提供，许可证正文在 `LICENSE`。
