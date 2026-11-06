# R8 for every Android app's release build (applied from the root build.gradle.kts):
# shrink and optimize, never rename. The source is public, so renaming hides nothing, and readable
# names keep stack traces and the `::class.simpleName` in log and error messages meaningful
# without a mapping file.
-dontobfuscate

# ⚠⚠ Native code that looks Java classes up BY NAME. R8 cannot see those calls, so it strips the
# constructors and methods they reach, and the app aborts the first time the native side runs —
# not at launch, so only a run that reaches the feature catches it (on 2026-10-22 *Index now*
# killed Control: `NoSuchMethodError: ai.onnxruntime.NodeInfo.<init>`). These libraries ship no
# R8 rules of their own. Found by reading the class names out of each `.so` (`strings`); re-check
# when a native dependency is added or bumped.
-keep class ai.onnxruntime.** { *; }
-keep class com.google.mediapipe.** { *; }
# Keeping all of MediaPipe keeps two profiler/template methods whose proto classes the AAR does not
# ship; R8 used to drop those methods unseen. Nothing calls them.
-dontwarn com.google.mediapipe.proto.**
# MediaPipe hands its options to native code as protobuf-lite messages, which read their fields by
# reflection.
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }
# (MapLibre's native renderer reaches Java through JavaCPP too, but `maplibre-native-ffi` ships the
# rules for it — checked 2026-10-22. The QR scanner is ZXing, pure Java with no reflection, and
# needs no rule; ML Kit's `barhopper` keep went with ML Kit.)
