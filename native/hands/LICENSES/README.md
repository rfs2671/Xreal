# Licenses for libultra_hands.so and the bundled models

`libultra_hands.so` statically contains code from the components below. OpenCV,
ONNX Runtime and libc++ are linked dynamically (the app ships them already).

| Component | What is compiled in | License | File here |
| --- | --- | --- | --- |
| Monado @ 6c9804934324ac5a3e68d64938579dbbeb4d75b3 (Collabora et al.) | `src/xrt/tracking/hand/mercury/*`, subset of `src/xrt/auxiliary/{math,util,os,tracking,onnx}`, `src/xrt/include` headers | BSL-1.0 | `Monado-BSL-1.0.txt` |
| Monado `auxiliary/math/m_quatexpmap.cpp` (Collabora; Sensics, Inc.) | whole file | Apache-2.0 | `Apache-2.0.txt` |
| Monado `mercury/kine_lm/lm_rotations_ceres.inl` (derived from Ceres Solver, Google) | whole file | BSD-3-Clause | `BSD-3-Clause.txt` |
| tinyceres (Monado `src/external/tinyceres`, from Ceres Solver, Google) | header templates (jet, tiny_solver) | BSD-3-Clause | `tinyceres-LICENSE.txt` |
| cJSON (Monado `src/external/cjson`, Dave Gamble and contributors) | `cJSON.c` | MIT | `cJSON-LICENSE.txt` |
| Eigen 3.4.0 | header templates | MPL-2.0 (a few headers BSD-3-Clause / Apache-2.0) | `Eigen-COPYING.*.txt` |
| XR Gate (vladoshub/xr-gate @ 56010adf) Mercury overlay: `xr_mercury_runtime_c_api.{h,cpp}`, `xr_mercury_quality_filter.hpp`, `xr_mercury_tracking_diagnostics.hpp` | whole files | BSL-1.0 (SPDX headers in the files) | `XRGate-mercury-overlay-BSL-1.0.txt` |
| XR Gate patches to Monado (`patches/monado/*.patch`) | applied to Mercury sources | repository license MIT; patched files remain BSL-1.0 | `XRGate-LICENSE-MIT.txt` |
| libultra_hands additions (`src/ext/*`, `src/patches/0001-ultra-hands.patch`) | whole files | BSL-1.0 | `Monado-BSL-1.0.txt` |
| Android NDK r27c runtime bits linked statically into every NDK .so: LLVM libunwind, compiler-rt builtins; libc++ header templates | objects/templates | Apache-2.0 WITH LLVM-exception (plus MIT/NCSA for older parts) | `NDK-LLVM-toolchain-NOTICE.txt` |
| OpenCV 4.12.0 (dynamic; inline header code only) | inline functions from headers | Apache-2.0 | `Apache-2.0.txt` |
| ONNX Runtime 1.22.0 (dynamic; C API header only) | nothing but declarations | MIT | `ONNXRuntime-LICENSE-MIT.txt` |

## Models (`models/*.onnx`)

`grayscale_detection_160x160.onnx` and `grayscale_keypoint_jan18.onnx` are the
Mercury models from
https://gitlab.freedesktop.org/monado/utilities/hand-tracking-models
(commit 37a8a81bc8f433ac6cbdf2471909d2bac74beca1, Git LFS).

That repository contains **no license file and no license metadata**. The same
two LFS objects (identical sha256) are also shipped in the model author's
`moshimeow/mercury_steamvr_driver` repository, whose only license file is
`LICENSES/BSL-1.0.txt`; XR Gate redistributes them on that basis ("Repository
license file packaged by upstream: BSL-1.0 ... does not relicense or
independently certify the provenance of the model weights"). `Models-BSL-1.0.txt`
is that file. Treat "BSL-1.0" for the weights as the upstream projects'
practice, not as an explicit grant inside the models repository.
