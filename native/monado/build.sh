#!/usr/bin/env bash
# Rebuilds everything in this folder from a clean clone: Monado at the pinned commit with the three patches, built
# out of process for Android arm64 with the package name dev.ultraxr baked in.
#
#   ./build.sh                 the runtime libraries and the two AARs
#   HELLO_XR=1 ./build.sh      also Khronos hello_xr (Vulkan and OpenGL ES debug APKs)
#   HOSTTEST=1 ./build.sh      also the driver test on this machine (needs cmake, ninja, a C/C++ compiler,
#                              libvulkan-dev, libudev-dev)
#   SMOKE=1 ./build.sh         also the smoke test host app (smoketest/), which compiles the Kotlin drop-ins
#
# Needs: git, python3, JDK 17 or 21, glslangValidator (Ubuntu: glslang-tools), the Android SDK with NDK
# 27.2.12479018 and CMake 3.22.1 (Gradle fetches platform 35 and build-tools 34.0.0 from dl.google.com if the
# licences are accepted). Network: gitlab.freedesktop.org, gitlab.com (Eigen 5.0.0), github.com (hello_xr),
# services.gradle.org, Maven Central, Google Maven, the Gradle plugin portal.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORK="${WORK:-/home/user/build/monado/rebuild}"
OUT="${OUT:-$HERE}"
export ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
NDK_VERSION="${NDK_VERSION:-27.2.12479018}"
PACKAGE="${PACKAGE:-dev.ultraxr}"
JOBS="${JOBS:-2}"
MONADO_URL=https://gitlab.freedesktop.org/monado/monado.git
MONADO_COMMIT=ee03722885e777462708841223b60affe6873ac2
OPENXR_SDK_URL=https://github.com/KhronosGroup/OpenXR-SDK-Source.git
OPENXR_SDK_TAG=release-1.1.65

NDK_BIN="$ANDROID_HOME/ndk/$NDK_VERSION/toolchains/llvm/prebuilt/linux-x86_64/bin"
BUILD_TOOLS="$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1)"
[ -x "$NDK_BIN/llvm-strip" ] || { echo "NDK $NDK_VERSION not found under $ANDROID_HOME/ndk" >&2; exit 1; }
command -v glslangValidator >/dev/null || { echo "glslangValidator not found (apt install glslang-tools)" >&2; exit 1; }

# Ninja takes its job count from the CPUs it may run on, and the Android Gradle plugin gives it no -j: pin the whole
# build to $JOBS CPUs so it stays inside its share of the machine.
LIMIT=()
if command -v taskset >/dev/null; then
    CPUS="$(nproc --all)"
    LIMIT=(taskset -c "$((CPUS - JOBS))-$((CPUS - 1))")
fi
GRADLE_ARGS=(--no-daemon --max-workers="$JOBS")

mkdir -p "$WORK" "$OUT/jniLibs/arm64-v8a" "$OUT/aar"

# 1. Monado at the pinned commit, with the patches in order.
SRC="$WORK/monado"
if [ ! -d "$SRC/.git" ]; then
    GIT_LFS_SKIP_SMUDGE=1 git clone "$MONADO_URL" "$SRC"
fi
git -C "$SRC" checkout -q --force "$MONADO_COMMIT"
git -C "$SRC" clean -q -fd -- src build.gradle
for patch in "$HERE"/patches/0001-monado-pocketxr.patch "$HERE"/patches/0002-ultraxr-build-on-linux.patch \
             "$HERE"/patches/0003-ultraxr-hands-aim-recenter.patch; do
    git -C "$SRC" apply --check "$patch"
    git -C "$SRC" apply "$patch"
    echo "applied $(basename "$patch")"
done
echo "sdk.dir=$ANDROID_HOME" > "$SRC/local.properties"

# 2. Build. Pocket XR's docs name :src:xrt:targets:openxr_android:assembleOutOfProcessRelease; that also assembles
# Monado's own app, whose Cardboard module needs a library from jitpack.io. Only its native libraries are wanted, so
# the native build task is run instead: the same CMake build, with no jitpack.
(cd "$SRC" && "${LIMIT[@]}" ./gradlew "${GRADLE_ARGS[@]}" \
    -Pandroid.injected.build.abi=arm64-v8a \
    -PxrtAndroidPackage="$PACKAGE" \
    -PxrtNdkVersion="$NDK_VERSION" \
    :src:xrt:targets:openxr_android:externalNativeBuildOutOfProcessRelease \
    :src:xrt:ipc:assembleRelease \
    :src:xrt:auxiliary:assembleRelease)

# 3. Collect: the libraries stripped as the Android Gradle plugin strips them, and the AARs under Pocket XR's names.
OBJ="$(ls -d "$SRC"/src/xrt/targets/openxr_android/build/intermediates/cxx/RelWithDebInfo/*/obj/arm64-v8a | head -1)"
for lib in libopenxr_monado.so libmonado-service.so; do
    "$NDK_BIN/llvm-strip" --strip-unneeded -o "$OUT/jniLibs/arm64-v8a/$lib" "$OBJ/$lib"
done
cp "$SRC/src/xrt/ipc/android/build/outputs/aar/ipc-release.aar" "$OUT/aar/monado-ipc.aar"
cp "$SRC/src/xrt/auxiliary/android/build/outputs/aar/auxiliary-release.aar" "$OUT/aar/monado-auxiliary.aar"

# 4. Check what was built.
for lib in "$OUT"/jniLibs/arm64-v8a/*.so; do
    echo "== $(basename "$lib")"
    "$NDK_BIN/llvm-readelf" -hW "$lib" | grep -E "Machine"
    "$NDK_BIN/llvm-readelf" -lW "$lib" | grep LOAD
    if "$NDK_BIN/llvm-readelf" -lW "$lib" | grep LOAD | grep -vq "0x4000$"; then
        echo "a LOAD segment of $lib is not 16 KB aligned" >&2; exit 1
    fi
    "$NDK_BIN/llvm-readelf" -dW "$lib" | grep NEEDED
done
grep -aq "$PACKAGE" "$OUT/jniLibs/arm64-v8a/libopenxr_monado.so" || { echo "package name not baked in" >&2; exit 1; }
"$NDK_BIN/llvm-nm" -D --defined-only "$OUT/jniLibs/arm64-v8a/libmonado-service.so" | grep -c "PocketFeed_native" \
    | xargs echo "PocketFeed JNI entries:"

# 5. Optional: the driver's host test, through Monado's own space overseer and hand tracker.
if [ "${HOSTTEST:-0}" = 1 ]; then
    EIGEN_DIR="$SRC/src/xrt/targets/openxr_android/build/intermediates/eigen-build"
    sed "s#/home/user/build/monado/hosttest#$HERE/hosttest#" "$HERE/hosttest/inject.cmake" > "$WORK/inject.cmake"
    sed "s#/home/user/build/monado/hosttest#$HERE/hosttest#" "$HERE/hosttest/test.cmake" > "$WORK/test.cmake"
    sed -i "s#$HERE/hosttest/test.cmake#$WORK/test.cmake#" "$WORK/inject.cmake"
    cmake -G Ninja -S "$SRC" -B "$WORK/host" -DCMAKE_BUILD_TYPE=Debug -DEigen3_DIR="$EIGEN_DIR" \
        -DXRT_FEATURE_SERVICE=OFF -DBUILD_TESTING=OFF -DXRT_HAVE_OPENGL=OFF -DXRT_HAVE_SDL2=OFF -DXRT_MODULE_IPC=OFF \
        -DCMAKE_PROJECT_INCLUDE="$WORK/inject.cmake" > "$WORK/host-configure.log"
    ninja -C "$WORK/host" -j"$JOBS" pxtest > "$WORK/host-build.log"
    "$WORK/host/pxtest" | grep -E "^(ok|FAIL|ALL|FAILED)"
fi

# 6. Optional: Khronos hello_xr as debug APKs. Changed only to build here (patches/hello_xr-build-here.diff).
if [ "${HELLO_XR:-0}" = 1 ]; then
    SDK="$WORK/OpenXR-SDK-Source"
    [ -d "$SDK/.git" ] || GIT_LFS_SKIP_SMUDGE=1 git clone --depth 1 --branch "$OPENXR_SDK_TAG" "$OPENXR_SDK_URL" "$SDK"
    git -C "$SDK" checkout -q --force
    git -C "$SDK" apply "$HERE/patches/hello_xr-build-here.diff"
    echo "sdk.dir=$ANDROID_HOME" > "$SDK/src/tests/hello_xr/local.properties"
    (cd "$SDK/src/tests/hello_xr" && "${LIMIT[@]}" ./gradlew "${GRADLE_ARGS[@]}" assembleVulkanDebug assembleOpenGLESDebug)
    [ -f "$HOME/.android/debug.keystore" ] || { echo "no debug keystore; build any debug APK once" >&2; exit 1; }
    for api in Vulkan OpenGLES; do
        name="hello_xr-$(echo "$api" | tr 'A-Z' 'a-z')-debug.apk"
        # The Android Gradle plugin 8.1 this sample pins aligns the stored libraries to 4 KB: realign to 16 KB.
        "$BUILD_TOOLS/zipalign" -f -P 16 4 "$SDK/src/tests/hello_xr/build/outputs/apk/$api/debug/hello_xr-$api-debug.apk" "$WORK/$name"
        "$BUILD_TOOLS/apksigner" sign --ks "$HOME/.android/debug.keystore" --ks-pass pass:android --key-pass pass:android \
            --ks-key-alias androiddebugkey --v4-signing-enabled false --out "$OUT/$name" "$WORK/$name"
    done
    cp "$OUT/hello_xr-vulkan-debug.apk" "$OUT/hello_xr.apk"
fi

# 7. Optional: the smoke test host app, which compiles the Kotlin drop-ins against the AARs (debug, and release with R8).
if [ "${SMOKE:-0}" = 1 ]; then
    # The app's sources are the drop-ins of kotlin/, taken from the output folder.
    [ "$OUT" = "$HERE" ] || { rm -rf "${OUT:?}/kotlin"; cp -r "$HERE/kotlin" "$OUT/kotlin"; }
    rm -rf "${WORK:?}/smoketest"
    cp -r "$HERE/smoketest" "$WORK/smoketest"
    echo "sdk.dir=$ANDROID_HOME" > "$WORK/smoketest/local.properties"
    (cd "$WORK/smoketest" && "${LIMIT[@]}" ./gradlew "${GRADLE_ARGS[@]}" -PmonadoOut="$OUT" :app:assembleDebug :app:assembleRelease)
    cp "$WORK/smoketest/app/build/outputs/apk/debug/app-debug.apk" "$OUT/ultraxr-runtime-smoketest-debug.apk"
fi

echo "done: $OUT"
