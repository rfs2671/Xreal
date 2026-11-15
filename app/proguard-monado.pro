# Monado's Java side: reached from native code by name (JNI, DexClassLoader from the OpenXR application's process)
# and through Binder. The AARs carry no consumer rules, so the app must keep them itself.
-keep class org.freedesktop.monado.** { *; }
-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }
# The service and provider named in the manifest.
-keep class dev.ultraxr.openxr.** { *; }
# monado-ipc.aar's own MonadoService is a Hilt entry point. It is removed from the manifest and never loaded, so its
# Hilt and inject references are not on the classpath.
-dontwarn dagger.hilt.**
-dontwarn dagger.**
-dontwarn javax.inject.**
# Monado's Client, MonadoView, SystemUiController and ActivityLifecycleListener are loaded into the OpenXR
# application's process with a DexClassLoader whose parent is the application's own class loader, so a class is
# looked up in the application first. Kotlin's runtime must therefore keep its real names here: renamed to "a.b" it
# could resolve to an unrelated "a.b" of a minified application.
-keepnames class kotlin.** { *; }
