# Added to Control's release R8 run ONLY under -Ptenshu.control.testBuildType=release (see
# control/build.gradle.kts): the instrumented test APK uses the app's copy of every library the two
# share, so what the test runner reaches must survive the app's R8 pass.
-keep class androidx.tracing.** { *; }
-keep class kotlin.** { *; }
