package org.tenshu.core.common

import java.nio.file.Files
import java.nio.file.Path

/**
 * A fresh temp directory under a root **short enough to bind a Unix-domain socket in**.
 *
 * ⚠⚠ Why not plain `Files.createTempDirectory`: on macOS `$TMPDIR` is a ~48-character
 * `/var/folders/…/T/` path, and the usable `sun_path` maximum there is 102 bytes, measured — a
 * socket under it flakes on the random suffix's length (`SocketDirectoryTest.tempRoot`). So the
 * socket tests use `/tmp`.
 *
 * ⚠⚠ Why not `Path.of("/tmp")` either: on Windows that is `\tmp` **on the current drive**, which a
 * developer machine may happen to have and a CI runner (checkout on `D:`) does not — every socket
 * test then fails with "no such file", looking like a real break. Windows falls back to
 * `java.io.tmpdir` (`C:\Users\<u>\AppData\Local\Temp`), well inside its ~108-byte limit.
 */
fun shortTempDirectory(prefix: String): Path {
    val tmp = Path.of("/tmp")
    val root = if (Files.isDirectory(tmp) && !isWindows()) tmp else Path.of(System.getProperty("java.io.tmpdir"))
    return Files.createTempDirectory(root, prefix)
}

private fun isWindows(): Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)
