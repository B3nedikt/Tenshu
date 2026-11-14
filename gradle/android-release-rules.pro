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
# ⚠⚠ MediaPipe logs through Flogger, and Flogger finds its caller by COUNTING STACK FRAMES:
# `StackBasedCallerFinder.findLoggingClass` skips exactly one frame (its own) and names the logger
# after the frame below `FluentLogger`. R8 inlined it into `FluentLogger.forEnclosingClass`, the
# count landed on the wrong frame, and MediaPipe's `Graph` failed its static initialiser ("no caller
# found on the stack for: …FluentLogger") — on 2026-11-11 face detection never ran in the shipped
# Control. Kept methods are never inlined; `Platform` also loads its backend by name. Two guards:
# scripts/lib/check_stack_frames.py checks the release dex still has these methods (the release
# script runs it), and `ReleaseStackWalkerInstrumentedTest` runs MediaPipe inside the R8 build on an
# emulator (by hand; see docs/how-to/android-release.md).
-keep class com.google.common.flogger.** { *; }
# (MapLibre's native renderer reaches Java through JavaCPP too, but `maplibre-native-ffi` ships the
# rules for it — checked 2026-10-22. The QR scanner is ZXing, pure Java with no reflection, and
# needs no rule; ML Kit's `barhopper` keep went with ML Kit.)
