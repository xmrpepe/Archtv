# Classes started by name from native code / app_process / reflection.
-keep class dev.archdock.audio.NativeAudio { *; }
-keep class dev.archdock.perf.NativePerf { *; }
-keep class com.termux.x11.** { *; }
-keep class com.github.luben.zstd.** { *; }
-dontwarn com.github.luben.zstd.**
-dontwarn org.apache.commons.compress.**
-dontwarn org.brotli.dec.**
-dontwarn org.tukaani.xz.**
-dontwarn org.objectweb.asm.**
