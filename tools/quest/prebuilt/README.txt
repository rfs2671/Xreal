libopenxr_loader.so here is the open OpenXR dispatcher, github.com/Molier/openxr-dispatcher-open at commit 68479ea,
with monado-is-not-pico.patch applied (one line: Monado lists Pico's controller extension and was taken for a Pico
runtime, which made the dispatcher withhold Meta's structures and use Pico bindings), built with NDK 27.2 for
arm64-v8a (android-29, Release). MIT licence: see LICENSE-openxr-dispatcher-open.
