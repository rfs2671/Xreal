# Licences of what is in this folder

Checked by reading SPDX tags and licence files in the trees that were built; not a legal review. Pocket XR's own
note still stands (docs/OPENXR.md, "Before a public release"): check the licences of everything Monado pulls in and
add its notices to the app.

| What | Licence | Text here |
| --- | --- | --- |
| Monado (gitlab.freedesktop.org/monado/monado, commit ee037228): `libopenxr_monado.so`, `libmonado-service.so`, `monado-ipc.aar`, `monado-auxiliary.aar` | BSL-1.0 | BSL-1.0.txt |
| Pocket XR's Monado changes, `patches/0001-monado-pocketxr.patch` | BSL-1.0 (stated at the top of the patch); Pocket XR itself is GPL-3.0 | BSL-1.0.txt, GPL-3.0.txt |
| `patches/0002-ultraxr-build-on-linux.patch` | BSL-1.0 | BSL-1.0.txt |
| `patches/0003-ultraxr-hands-aim-recenter.patch`, `kotlin/`, `smoketest/`, `hosttest/`, `build.sh`, `NOTES.md` | GPL-3.0-or-later | GPL-3.0.txt |
| Inside the Monado libraries: android-jni-wrap (BSL-1.0), jnipp (MIT), cJSON (MIT), nanopb (Zlib), Hungarian (BSD-2-Clause), tinyceres (BSD-3-Clause), glad and Khronos headers (CC0-1.0 / Apache-2.0 / MIT), stb (MIT or Unlicense), Eigen 5.0.0 headers (MPL-2.0, some BSD-3-Clause and Apache-2.0 parts), LLVM libc++ and compiler-rt from the NDK, linked statically (Apache-2.0 with LLVM exception) | as named | the SPDX texts here, `third-party-*.txt` |
| `hello_xr*.apk`: Khronos OpenXR-SDK-Source 1.1.65 hello_xr and loader, with jsoncpp, jnipp, android-jni-wrappers | Apache-2.0 (loader: Apache-2.0 OR MIT) | Apache-2.0.txt, MIT.txt, LicenseRef-jsoncpp-public-domain.txt |

`libmonado-service.so` contains the GPL-3.0-or-later code of patch 0003 (the hand joint device, `pocketxr_hands.c`,
and the changes to the pocketxr driver and its JNI entries). It runs only in the app's own process.
`libopenxr_monado.so`, the library that other apps' processes load, contains none of it: only BSL-1.0 Monado and the
BSL-1.0 parts of Pocket XR's patch. `monado-ipc.aar` holds `PocketFeed.class`, built from Pocket XR's BSL-1.0 file
plus the GPL-3.0-or-later declarations of patch 0003; an OpenXR application's process loads classes from the app's
APK (Client, MonadoView, ...), never PocketFeed.
