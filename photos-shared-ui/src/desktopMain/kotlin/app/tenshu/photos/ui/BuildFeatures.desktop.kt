package app.tenshu.photos.ui

/**
 * ⚠⚠ True on **macOS only**, mirroring Control's desktop `faceClusteringRunsHere`: macOS Control groups
 * faces with Apple Vision (`ios-face-grouping.md` §10.8), and Windows and Linux Control cannot. Asked
 * by OS rather than by this process's FFM: the fact is about the Control on this Mac, which ships its
 * own JDK 25, not about Photos' runtime.
 */
actual val faceGroupingRunsHere: Boolean = System.getProperty("os.name").orEmpty().startsWith("Mac", ignoreCase = true)
