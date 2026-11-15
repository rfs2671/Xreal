# Native components

The APK ships prebuilt arm64 libraries in `app/src/main/jniLibs/arm64-v8a/`. Each was built
from pinned upstream sources with the scripts and patches kept here, so it can be rebuilt.

| Library | What | Source | Rebuild |
|---|---|---|---|
| `libultra_vio.so` | Basalt visual-inertial odometry behind a small C API | `vio/` | `vio/src/build_android.sh` |
| `libultra_hands.so` | Monado's Mercury hand tracker with XR Gate's C API | `hands/` | `hands/src/build_android.sh` |
| `libopenxr_monado.so`, `libmonado-service.so` | Monado OpenXR runtime with Pocket XR's Android driver plus hand joints | `monado/` | `monado/build.sh` |
| `libopencv_java4.so` | OpenCV 4.12.0, from the official Maven AAR `org.opencv:opencv:4.12.0` | unmodified | download |
| `libonnxruntime.so` | ONNX Runtime 1.22.0, from `com.microsoft.onnxruntime:onnxruntime-android:1.22.0` | unmodified | download |
| `libultraxr.so` | This app's JNI glue and USB isochronous reader | `app/src/main/cpp/` | built by Gradle |

Each folder has a `NOTES.md` with the exact commits, what was patched and why, the input
conventions, and what was and was not verified. Licence texts are in each `LICENSES/` folder.

The scripts expect the Android NDK 27.2.12479018 and the two AARs above unpacked under
`/home/user/deps`; adjust the paths at the top of each script for another machine.
